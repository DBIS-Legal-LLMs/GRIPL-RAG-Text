"""
Request and response schemas for the RAG query API.
"""
from pydantic import BaseModel, Field
from enum import Enum


class QueryMode(str, Enum):
    """LightRAG search modes."""
    naive = "naive"
    local = "local"
    global_ = "global"
    hybrid = "hybrid"


class QueryRequest(BaseModel):
    query: str = Field(
        ...,
        description="The query text to search for in the knowledge graph.",
        min_length=1,
        max_length=10000,
    )
    mode: QueryMode = Field(
        default=QueryMode.hybrid,
        description="LightRAG search mode: naive, local, global, or hybrid.",
    )


class QueryResponse(BaseModel):
    query: str = Field(
        ...,
        description="The original query that was sent.",
    )
    mode: str = Field(
        ...,
        description="The search mode that was used.",
    )
    response: dict = Field(
        ...,
        description="Parsed context with entities, relationships, and documents.",
    )
    status: str = Field(
        default="success",
        description="Status of the query: 'success' or 'error'.",
    )


class HealthResponse(BaseModel):
    """Health check response."""
    status: str = "ok"
    service: str = "gripl-rag"
