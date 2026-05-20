import re
import json
import logging
from typing import List, Dict

logger = logging.getLogger(__name__)


def extract_valid_json_objects(text: str) -> List[dict]:
    """
    Extract JSON objects from messy text using brace counting.
    Safely ignores malformed JSON.
    """
    objects = []
    brace_count = 0
    obj_str = ""
    in_string = False
    escape = False

    for char in text:
        next_escape = False

        if char == '\\' and in_string and not escape:
            # Mark that the next character is escaped
            next_escape = True
        elif char == '"' and not escape:
            in_string = not in_string

        escape = next_escape

        if not in_string:
            if char == '{':
                if brace_count == 0:
                    obj_str = ""
                brace_count += 1
            elif char == '}':
                brace_count -= 1

        if brace_count > 0:
            obj_str += char
        elif brace_count == 0 and char == '}' and obj_str:
            obj_str += char
            try:
                parsed = json.loads(obj_str, strict=False)
                if isinstance(parsed, dict):
                    objects.append(parsed)
            except json.JSONDecodeError:
                pass
            obj_str = ""

        if brace_count < 0:
            brace_count = 0
            obj_str = ""

    return objects


def normalize_key(value: str) -> str:
    """Normalize entity names for deduplication."""
    return value.strip().lower()


def clean_text(value: str) -> str:
    """
    Remove soft hyphens (U+00AD) that appear in PDF-extracted text,
    collapse internal whitespace, and strip surrounding whitespace.
    """
    return re.sub(r"\s+", " ", value.replace("\u00ad", "")).strip()


def make_preview(content: str, max_chars: int = 200) -> str:
    """Generate a word-boundary-safe preview of content."""
    if len(content) <= max_chars:
        return content
    return content[:max_chars].rsplit(" ", 1)[0] + "…"


def split_description(desc: str) -> List[str]:
    """Split descriptions on <SEP> into a clean list."""
    if not desc:
        return []
    return [clean_text(d) for d in str(desc).split("<SEP>") if d.strip()]


def parse_rag_response(raw_response: str) -> Dict:
    """
    Parse messy RAG response into structured data ready for UI presentation:
    - entities
    - relationships
    - documents
    - meta
    """

    result = {
        "entities": [],
        "relationships": [],
        "documents": [],
        "meta": {}
    }

    if not raw_response or not isinstance(raw_response, str):
        return result

    # -------------------------------
    # Parse Reference Document List
    # Format: [1] file_path_or_name
    # This maps numeric reference_ids in chunks to actual source names.
    # -------------------------------
    ref_map: dict[str, str] = {}
    ref_section = re.search(
        r"Reference Document List[^\n]*\n+```\n(.*?)```",
        raw_response,
        re.DOTALL,
    )
    if ref_section:
        for line in ref_section.group(1).splitlines():
            m = re.match(r"\[(\d+)\]\s+(.+)", line.strip())
            if m:
                ref_id = m.group(1)
                file_name = m.group(2).strip()
                # Keep only the final path component, stripping known doc extensions.
                # Why: filenames like "..._v2.0_en" contain a dot inside the stem; a
                # generic `\.[^.]+$` would eat ".0_en" and break the PDF lookup.
                file_name = file_name.replace("\\", "/").split("/")[-1]
                file_name = re.sub(r"\.(pdf|txt|md|html?|docx?)$", "", file_name, flags=re.IGNORECASE)
                ref_map[ref_id] = file_name

    # -------------------------------
    # Split Sections (robust)
    # -------------------------------
    sections = re.split(
        r"(?i)(Knowledge Graph Data \(Entity\)|Knowledge Graph Data \(Relationship\)|Document Chunks)",
        raw_response
    )

    current_section = None
    section_texts = {
        "entity": "",
        "relationship": "",
        "document": ""
    }

    for part in sections:
        part_lower = part.lower().strip()

        if "knowledge graph data (entity)" in part_lower:
            current_section = "entity"
        elif "knowledge graph data (relationship)" in part_lower:
            current_section = "relationship"
        elif "document chunks" in part_lower:
            current_section = "document"
        elif current_section:
            section_texts[current_section] += part

    if not any(section_texts.values()):
        logger.warning(
            "parse_rag_response: no known section headers found in response — "
            "entities/relationships/documents will be empty. "
            "Raw response (first 500 chars): %s",
            raw_response[:500],
        )

    # -------------------------------
    # Extract JSON objects
    # -------------------------------
    entity_objects = extract_valid_json_objects(section_texts["entity"])
    relationship_objects = extract_valid_json_objects(section_texts["relationship"])
    document_objects = extract_valid_json_objects(section_texts["document"])

    # -------------------------------
    # Entity Processing
    # -------------------------------
    entity_map = {}  # normalized_name -> entity_id
    entity_id_counter = 1

    for obj in entity_objects:
        name = str(obj.get("entity") or obj.get("entity_name") or obj.get("name") or "").strip()
        if not name:
            continue

        norm_name = normalize_key(name)
        if norm_name in entity_map:
            continue

        entity_id = f"entity_{entity_id_counter}"
        entity_id_counter += 1
        entity_map[norm_name] = entity_id

        entity_type = str(obj.get("type") or obj.get("entity_type") or "Unknown")
        descriptions = split_description(obj.get("description") or obj.get("desc") or "")

        result["entities"].append({
            "id": entity_id,
            "label": name,
            "type": entity_type,
            "description": descriptions,
            "description_text": "\n".join(descriptions),
        })

    # -------------------------------
    # Document Processing
    # -------------------------------
    if document_objects:
        logger.info("parse_rag_response: first doc chunk keys=%s sample=%s",
                    list(document_objects[0].keys()), document_objects[0])

    doc_id_counter = 1

    for obj in document_objects:
        content = clean_text(str(
            obj.get("content") or obj.get("text") or obj.get("chunk_content") or ""
        ))

        if not content:
            continue

        doc_id = f"doc_{doc_id_counter}"
        doc_id_counter += 1

        raw_ref = next(
            (
                str(v).strip()
                for v in [
                    obj.get("reference_id"),
                    obj.get("full_doc_id"),
                    obj.get("file_path"),
                    obj.get("file_name"),
                    obj.get("doc_id"),
                    obj.get("source_id"),
                ]
                if v is not None and str(v).strip()
            ),
            None,
        )
        # Resolve numeric reference_id to actual source name via the reference map
        resolved_ref = ref_map.get(raw_ref, raw_ref) if raw_ref else None

        result["documents"].append({
            "id": doc_id,
            "source_document": resolved_ref,
            "content": content,
            "preview": make_preview(content)
        })

    # -------------------------------
    # Relationship Processing
    # -------------------------------
    seen_rels = set()

    for obj in relationship_objects:
        source = str(
            obj.get("source") or obj.get("entity1") or obj.get("src_id") or ""
        ).strip()

        target = str(
            obj.get("target") or obj.get("entity2") or obj.get("tgt_id") or ""
        ).strip()

        label = clean_text(str(
            obj.get("label")
            or obj.get("relation")
            or obj.get("relationship")
            or obj.get("description")
            or ""
        ))

        if not source or not target:
            continue

        # Ensure entities exist (auto-create if referenced but not declared)
        for node in [source, target]:
            norm = normalize_key(node)
            if norm not in entity_map:
                entity_id = f"entity_{entity_id_counter}"
                entity_id_counter += 1
                entity_map[norm] = entity_id

                result["entities"].append({
                    "id": entity_id,
                    "label": node,
                    "type": "Unknown",
                    "description": [],
                    "description_text": "",
                })

        source_id = entity_map[normalize_key(source)]
        target_id = entity_map[normalize_key(target)]

        # Deduplicate relationships.
        # BPMN flows are directed edges.
        rel_key = (source_id, target_id)
        if rel_key in seen_rels:
            continue
        seen_rels.add(rel_key)

        result["relationships"].append({
            "source": source_id,
            "source_label": source,
            "target": target_id,
            "target_label": target,
            "label": label
        })

    # -------------------------------
    # Top-level Metadata
    # -------------------------------
    result["meta"] = {
        "entity_count": len(result["entities"]),
        "relationship_count": len(result["relationships"]),
        "document_count": len(result["documents"])
    }

    return result