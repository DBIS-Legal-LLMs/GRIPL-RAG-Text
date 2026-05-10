from pathlib import Path
from typing import TypedDict

import fitz  # PyMuPDF


class LocateResult(TypedDict):
    page: int
    rects: list[dict]
    page_width: float
    page_height: float


def _search_page_for_chunks(page: fitz.Page, text: str) -> list[dict]:
    """Return bounding rects for as much of `text` as can be found on `page`.

    Splits the text into sentences / phrases and searches for each one,
    collecting all hits. This covers chunks that span many lines.
    """
    rects: list[dict] = []
    seen: set[tuple] = set()

    # Split on sentence boundaries; fall back to word groups of ~10
    import re
    sentences = [s.strip() for s in re.split(r'(?<=[.!?])\s+', text) if len(s.strip()) >= 15]
    if not sentences:
        sentences = [text]

    for sentence in sentences:
        # Try the full sentence first, then a 60-char prefix of it
        for fragment in [sentence, sentence[:60]]:
            if len(fragment) < 10:
                continue
            hits = page.search_for(fragment)
            for r in hits:
                key = (round(r.x0), round(r.y0), round(r.x1), round(r.y1))
                if key not in seen:
                    seen.add(key)
                    rects.append({"x0": r.x0, "y0": r.y0, "x1": r.x1, "y1": r.y1})
            if hits:
                break  # found with full sentence, no need to try prefix

    return rects


def locate_text_in_pdf(pdf_path: Path, query: str) -> LocateResult | None:
    """Search a PDF for query text and return the first matching page + bounding boxes.

    Tries progressively shorter prefixes (200 → 120 → 60 chars) to locate the
    page, then searches for the full query text on that page so the highlight
    covers the entire retrieved chunk rather than just the opening phrase.
    Returns None if no match is found on any page.
    """
    prefixes = [query[:n] for n in (200, 120, 60) if len(query) >= n]
    if not prefixes:
        prefixes = [query]

    doc = fitz.open(str(pdf_path))
    try:
        for prefix in prefixes:
            for page_index in range(len(doc)):
                page = doc[page_index]
                if not page.search_for(prefix):
                    continue

                # Found the page — now collect rects for the full text
                rect = page.rect
                rects = _search_page_for_chunks(page, query)

                # Fallback: if sentence splitting found nothing, use prefix rects
                if not rects:
                    rects = [
                        {"x0": r.x0, "y0": r.y0, "x1": r.x1, "y1": r.y1}
                        for r in page.search_for(prefix)
                    ]

                return {
                    "page": page_index,
                    "rects": rects,
                    "page_width": rect.width,
                    "page_height": rect.height,
                }
    finally:
        doc.close()

    return None
