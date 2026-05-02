package de.mertendieckmann.griplbackend.evaluation.metrics

import de.mertendieckmann.griplbackend.model.dto.EvaluationReportSummary
import de.mertendieckmann.griplbackend.model.dto.RagSummaryMetrics
import de.mertendieckmann.griplbackend.model.evaluation.EvaluationMetrics
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Thread-safe metrics accumulator for all test cases.
 */
class MetricsAccumulator {
    private val lock = Mutex()
    private var total: Int = 0
    private var passed: Int = 0
    private var errors: Int = 0
    private var amountOfRetries: Int = 0
    private var totalTruePositives: Int = 0
    private var totalFalsePositives: Int = 0
    private var totalFalseNegatives: Int = 0
    private var totalTrueNegatives: Int = 0

    // RAG metric sums + counts (per test case mean values)
    private var faithfulnessSum: Double = 0.0
    private var faithfulnessCount: Int = 0
    private var answerRelevancySum: Double = 0.0
    private var answerRelevancyCount: Int = 0
    private var ragSampleTotal: Int = 0
    private var ragFailedTotal: Int = 0
    private var ragEvaluatedTestCases: Int = 0

    suspend fun add(metrics: EvaluationMetrics) = lock.withLock {
        total++
        if (metrics.isSuccessful) passed++
        totalTruePositives += metrics.truePositives
        totalFalsePositives += metrics.falsePositives
        totalFalseNegatives += metrics.falseNegatives
        totalTrueNegatives += metrics.trueNegatives
        amountOfRetries += metrics.amountOfRetries ?: 0

        metrics.ragMetrics?.let { rag ->
            ragEvaluatedTestCases++
            ragSampleTotal += rag.sampleCount
            ragFailedTotal += rag.failedCount
            rag.faithfulness?.let { faithfulnessSum += it; faithfulnessCount++ }
            rag.answerRelevancy?.let { answerRelevancySum += it; answerRelevancyCount++ }
        }
    }

    suspend fun addError() = lock.withLock {
        total++
        errors++
    }

    fun toSummary(): EvaluationReportSummary {
        val precision = if (totalTruePositives + totalFalsePositives > 0)
            totalTruePositives.toDouble() / (totalTruePositives + totalFalsePositives)
        else 0.0

        val recall = if (totalTruePositives + totalFalseNegatives > 0)
            totalTruePositives.toDouble() / (totalTruePositives + totalFalseNegatives)
        else 0.0

        val f1Score = if (precision + recall > 0)
            2 * (precision * recall) / (precision + recall)
        else 0.0

        val accuracy = if (totalTruePositives + totalFalsePositives + totalFalseNegatives + totalTrueNegatives > 0)
            (totalTruePositives + totalTrueNegatives).toDouble() / (totalTruePositives + totalFalsePositives + totalFalseNegatives + totalTrueNegatives)
        else 0.0

        val ragSummary = if (ragEvaluatedTestCases > 0) {
            RagSummaryMetrics(
                faithfulnessMean = if (faithfulnessCount > 0) faithfulnessSum / faithfulnessCount else null,
                answerRelevancyMean = if (answerRelevancyCount > 0) answerRelevancySum / answerRelevancyCount else null,
                evaluatedTestCases = ragEvaluatedTestCases,
                totalSamples = ragSampleTotal,
                failedSamples = ragFailedTotal
            )
        } else null

        return EvaluationReportSummary(
            total = total,
            passed = passed,
            failed = (total - passed - errors).coerceAtLeast(0),
            error = errors,
            amountOfRetries = amountOfRetries,
            precision = precision,
            recall = recall,
            f1Score = f1Score,
            accuracy = accuracy,
            totalTruePositives = totalTruePositives,
            totalFalsePositives = totalFalsePositives,
            totalFalseNegatives = totalFalseNegatives,
            totalTrueNegatives = totalTrueNegatives,
            ragMetrics = ragSummary
        )
    }
}
