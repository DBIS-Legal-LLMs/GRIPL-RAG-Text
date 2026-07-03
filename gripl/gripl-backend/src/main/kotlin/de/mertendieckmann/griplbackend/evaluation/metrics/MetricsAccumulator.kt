package de.mertendieckmann.griplbackend.evaluation.metrics

import de.mertendieckmann.griplbackend.evaluation.ElementCategory
import de.mertendieckmann.griplbackend.model.dto.ElementTypeSummary
import de.mertendieckmann.griplbackend.model.dto.EvaluationReportSummary
import de.mertendieckmann.griplbackend.model.dto.RagSummaryMetrics
import de.mertendieckmann.griplbackend.model.evaluation.ElementTypeCounts
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
    private val perElementTypeTotals = mutableMapOf<String, ElementTypeCounts>()

    // RAG metric sums + counts (per test case mean values)
    private var faithfulnessSum: Double = 0.0
    private var faithfulnessCount: Int = 0
    private var contextUtilizationSum: Double = 0.0
    private var contextUtilizationCount: Int = 0
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
        metrics.perElementType.forEach { (category, counts) ->
            perElementTypeTotals[category] = (perElementTypeTotals[category] ?: ElementTypeCounts()) + counts
        }

        metrics.ragMetrics?.let { rag ->
            ragEvaluatedTestCases++
            ragSampleTotal += rag.sampleCount
            ragFailedTotal += rag.failedCount
            rag.faithfulness?.let { faithfulnessSum += it; faithfulnessCount++ }
            rag.contextUtilization?.let { contextUtilizationSum += it; contextUtilizationCount++ }
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

        // Per-category metrics in stable display order (activities, events, gateways, data, other).
        // The "other" bucket is dropped when empty — it only carries unresolvable ids.
        val perElementTypeSummary = perElementTypeTotals.entries
            .sortedBy { (key, _) -> ElementCategory.entries.indexOfFirst { it.key == key } }
            .filter { (key, c) ->
                key != ElementCategory.OTHER.key ||
                    (c.truePositives + c.falsePositives + c.falseNegatives) > 0
            }
            .associate { (key, c) ->
                val p = if (c.truePositives + c.falsePositives > 0)
                    c.truePositives.toDouble() / (c.truePositives + c.falsePositives) else 0.0
                val r = if (c.truePositives + c.falseNegatives > 0)
                    c.truePositives.toDouble() / (c.truePositives + c.falseNegatives) else 0.0
                val f1 = if (p + r > 0) 2 * (p * r) / (p + r) else 0.0
                val total = c.truePositives + c.falsePositives + c.falseNegatives + c.trueNegatives
                val acc = if (total > 0) (c.truePositives + c.trueNegatives).toDouble() / total else 0.0
                key to ElementTypeSummary(
                    displayName = ElementCategory.entries.firstOrNull { it.key == key }?.displayName ?: key,
                    truePositives = c.truePositives,
                    falsePositives = c.falsePositives,
                    falseNegatives = c.falseNegatives,
                    trueNegatives = c.trueNegatives,
                    precision = p,
                    recall = r,
                    f1Score = f1,
                    accuracy = acc
                )
            }

        val ragSummary = if (ragEvaluatedTestCases > 0) {
            RagSummaryMetrics(
                faithfulnessMean = if (faithfulnessCount > 0) faithfulnessSum / faithfulnessCount else null,
                contextUtilizationMean = if (contextUtilizationCount > 0) contextUtilizationSum / contextUtilizationCount else null,
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
            ragMetrics = ragSummary,
            perElementType = perElementTypeSummary
        )
    }
}
