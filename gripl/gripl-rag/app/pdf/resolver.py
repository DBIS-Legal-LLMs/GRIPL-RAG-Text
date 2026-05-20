from pathlib import Path

from app.config import settings

_PDF_SUBDIRS = [
    Path("data") / "regulatory",
    Path("data") / "guidance" / "edpb",
]


def _normalise(name: str) -> str:
    return name.lower().replace("-", "_").replace(" ", "_")


def resolve_pdf_path(document_name: str) -> Path | None:
    """Map a document_name stem (no extension) to the PDF file on disk.

    Searches all known PDF data directories. Matching is case-insensitive and
    treats hyphens and underscores as equivalent, because full_doc_id values
    use hyphens while some filenames use underscores.
    """
    base = Path(settings.rag_working_dir).parent
    needle = _normalise(document_name)

    prefix_match: Path | None = None
    for subdir in _PDF_SUBDIRS:
        directory = base / subdir
        if not directory.is_dir():
            continue
        for pdf_file in directory.glob("*.pdf"):
            stem_norm = _normalise(pdf_file.stem)
            if stem_norm == needle:
                return pdf_file
            # Fallback: tolerate truncated identifiers (e.g. "..._v2" → "..._v2.0_en").
            # Only kicks in if no exact match is found across all dirs.
            if prefix_match is None and needle and stem_norm.startswith(needle):
                prefix_match = pdf_file

    return prefix_match
