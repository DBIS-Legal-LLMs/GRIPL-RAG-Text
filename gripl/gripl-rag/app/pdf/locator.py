from pathlib import Path
from typing import TypedDict
import re
import unicodedata

import fitz  # PyMuPDF
from rapidfuzz import fuzz


class LocateResult(TypedDict):
    page: int
    rects: list[dict]
    page_width: float
    page_height: float


_LIGATURES = {
    "ﬀ": "ff", "ﬁ": "fi", "ﬂ": "fl",
    "ﬃ": "ffi", "ﬄ": "ffl", "ﬅ": "st", "ﬆ": "st",
}
_NON_ALPHANUM = re.compile(r"[^\w\s]", re.UNICODE)
_WHITESPACE = re.compile(r"\s+")
_MIN_PAGE_SCORE = 65.0          # below this, the page is considered no match
_TOKEN_OVERLAP_FLOOR = 0.25     # skip pages whose token overlap with the chunk is too low


def _normalize(text: str) -> str:
    text = unicodedata.normalize("NFKC", text)
    for lig, repl in _LIGATURES.items():
        text = text.replace(lig, repl)
    text = text.replace("­", "")            # soft hyphen
    text = re.sub(r"-\s*\n\s*", "", text)        # join hyphenated line breaks
    text = text.lower()
    text = _NON_ALPHANUM.sub(" ", text)
    text = _WHITESPACE.sub(" ", text).strip()
    return text


def _tokenize(text: str) -> list[str]:
    return _normalize(text).split()


def _group_rects_by_line(matched_words: list[tuple]) -> list[dict]:
    """One bounding rectangle per visual line — like a normal text selection."""
    lines: dict[tuple[int, int], list[tuple]] = {}
    for w in matched_words:
        key = (int(w[5]), int(w[6]))  # block_no, line_no
        lines.setdefault(key, []).append(w)

    rects: list[dict] = []
    for key in sorted(lines.keys()):
        ws = lines[key]
        rects.append({
            "x0": min(w[0] for w in ws),
            "y0": min(w[1] for w in ws),
            "x1": max(w[2] for w in ws),
            "y1": max(w[3] for w in ws),
        })
    return rects


def _align_chunk_to_page(
    page: fitz.Page,
    chunk_tokens: list[str],
    chunk_text: str,
) -> tuple[float, list]:
    """Return (score, matched_raw_words) for the best alignment on this page."""
    raw_words = page.get_text("words")
    if not raw_words:
        return 0.0, []

    # Normalize each word and remember which raw word each normalized token came from.
    page_tokens: list[str] = []
    token_to_raw: list[int] = []
    for idx, w in enumerate(raw_words):
        for part in _normalize(w[4]).split():
            page_tokens.append(part)
            token_to_raw.append(idx)

    if not page_tokens:
        return 0.0, []

    # Cheap overlap prefilter so we skip clearly-irrelevant pages before the
    # O(n*m) alignment.
    chunk_set = set(chunk_tokens)
    page_set = set(page_tokens)
    overlap = len(chunk_set & page_set) / max(len(chunk_set), 1)
    if overlap < _TOKEN_OVERLAP_FLOOR:
        return 0.0, []

    # Build a joined page string + remember each token's char offset so we can
    # map the character-level rapidfuzz alignment back to whole words.
    parts: list[str] = []
    offsets: list[int] = []
    pos = 0
    for t in page_tokens:
        offsets.append(pos)
        parts.append(t)
        pos += len(t) + 1
    page_text = " ".join(parts)

    alignment = fuzz.partial_ratio_alignment(chunk_text, page_text)
    if alignment is None or alignment.score < _MIN_PAGE_SCORE:
        return 0.0, []

    dest_start = alignment.dest_start
    dest_end = alignment.dest_end

    matched_raw: set[int] = set()
    for i, off in enumerate(offsets):
        tok_end = off + len(page_tokens[i])
        if tok_end <= dest_start:
            continue
        if off >= dest_end:
            break
        matched_raw.add(token_to_raw[i])

    if not matched_raw:
        return 0.0, []

    return alignment.score, [raw_words[i] for i in sorted(matched_raw)]


def locate_text_in_pdf(pdf_path: Path, query: str) -> LocateResult | None:
    """Locate query text in PDF by fuzzy-aligning the chunk against each page.

    Words from the PDF are extracted with their bounding boxes, both sides are
    normalized (ligatures, hyphenation, case, punctuation), and rapidfuzz finds
    the best matching span per page. Matched words are then grouped by their
    visual line so the returned highlight is one clean rectangle per line —
    robust to whitespace, line-break, and ligature mismatches between the
    pre-extracted .txt chunks and the PDF text stream.
    """
    chunk_tokens = _tokenize(query)
    if not chunk_tokens:
        return None
    chunk_text = " ".join(chunk_tokens)

    doc = fitz.open(str(pdf_path))
    try:
        best_score = 0.0
        best_page_index = -1
        best_matched: list = []
        best_rect = None

        for page_index in range(len(doc)):
            page = doc[page_index]
            score, matched = _align_chunk_to_page(page, chunk_tokens, chunk_text)
            if not matched:
                continue
            # Tiebreak slightly in favor of pages that matched more words, so a
            # full-passage hit beats a page that happens to score 100 on a
            # short overlap.
            weighted = score + 0.05 * len(matched)
            if weighted > best_score:
                best_score = weighted
                best_page_index = page_index
                best_matched = matched
                best_rect = page.rect

        if best_page_index < 0 or not best_matched:
            return None

        return {
            "page": best_page_index,
            "rects": _group_rects_by_line(best_matched),
            "page_width": best_rect.width,
            "page_height": best_rect.height,
        }
    finally:
        doc.close()
