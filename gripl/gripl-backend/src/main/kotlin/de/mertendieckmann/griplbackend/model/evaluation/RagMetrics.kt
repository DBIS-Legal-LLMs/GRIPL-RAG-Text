package de.mertendieckmann.griplbackend.model.evaluation


data class RagMetrics(
    val faithfulness: Double? = null,
    val answerRelevancy: Double? = null,
    val contextUtilization: Double? = null,
    val contextRelevance: Double? = null,
    val sampleCount: Int = 0,
    val failedCount: Int = 0
)
