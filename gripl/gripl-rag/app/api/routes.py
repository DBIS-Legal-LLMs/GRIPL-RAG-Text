"""
FastAPI router for the RAG query API.

Exposes:
  POST /api/query    query the knowledge graph
  GET  /api/health   basic health/readiness check
"""
import logging
import asyncio
from fastapi import APIRouter, HTTPException
from lightrag import QueryParam

from app.schemas.query import (
    QueryRequest,
    QueryResponse,
    HealthResponse,
)
from app.rag.engine import create_rag_instance
from app.rag.parser import parse_rag_response

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/api", tags=["RAG"])

# ---------------------------------------------------------------------------
# Module-level RAG singleton (initialised lazily on first query)
# ---------------------------------------------------------------------------
_rag_instance = None
_rag_init_lock = asyncio.Lock()



async def _get_rag():
    """Return (and cache) the LightRAG instance."""
    global _rag_instance
    if _rag_instance is not None:
        return _rag_instance
    async with _rag_init_lock:
        if _rag_instance is None:
            logger.info("Initialising LightRAG instance …")
            _rag_instance = await create_rag_instance()
            logger.info("LightRAG instance ready.")
    return _rag_instance


# ---------------------------------------------------------------------------
# Endpoints
# ---------------------------------------------------------------------------

@router.get("/health", response_model=HealthResponse)
async def health_check():
    return HealthResponse()


@router.post("/query", response_model=QueryResponse)
async def query_rag(request: QueryRequest):
    logger.info("Received query: %.120s …  [mode=%s]", request.query, request.mode)

    try:
        rag = await _get_rag()

        # Map enum value
        mode = request.mode.value
        if mode == "global_":
            mode = "global"

        # only_need_context=True → return raw retrieved chunks,
        # not an LLM-generated answer.
        logger.debug("Querying mode=%s, only_need_context=True", mode)
        
        try:
            result = await asyncio.wait_for(
                rag.aquery(
                    request.query,
                    param=QueryParam(mode=mode, only_need_context=True),
                ),
                timeout=300.0
            )
        except asyncio.TimeoutError:
            logger.error("LightRAG query timed out")
            raise HTTPException(status_code=504, detail="RAG query timed out")

        logger.debug("LightRAG returned type %s", type(result))
        logger.debug("LightRAG returned: %s", repr(result)[:500])

        if result is None:
            result = ""

        # Parse the raw markdown/JSON string into structured dict
        parsed_result = parse_rag_response(result)
    
        logger.info("Query completed successfully (response length=%d, entities=%d, relationships=%d, documents=%d)", 
                    len(result), len(parsed_result["entities"]), 
                    len(parsed_result["relationships"]), len(parsed_result["documents"]))

        return QueryResponse(
            query=request.query,
            mode=mode,
            response=parsed_result,
            status="success",
        )

    except Exception as exc:
        logger.exception("Query failed: %s", exc)
        raise HTTPException(
            status_code=500,
            detail=f"RAG query failed: {exc}",
        )
