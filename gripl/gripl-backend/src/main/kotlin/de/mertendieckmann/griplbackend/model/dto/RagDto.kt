package de.mertendieckmann.griplbackend.model.dto

data class RagRequest(
    val query: String,
    val mode: String
)

data class RagResponseWrapper(
    val query: String,
    val mode: String,
    val status: String,
    val response: String
)
