package de.mertendieckmann.griplbackend.evaluation.service

import de.mertendieckmann.griplbackend.adapter.rag.RagasApiClient
import de.mertendieckmann.griplbackend.model.BpmnElement
import de.mertendieckmann.griplbackend.model.dto.AnalysisResponse
import de.mertendieckmann.griplbackend.model.dto.RagElementContext
import de.mertendieckmann.griplbackend.model.dto.RagasEvaluationRequest
import de.mertendieckmann.griplbackend.model.dto.RagasSampleRequest
import de.mertendieckmann.griplbackend.model.evaluation.RagMetrics
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service

/**
 * Builds Ragas samples from an [AnalysisResponse] (which carries the RAG
 * context per BPMN element) and delegates scoring to the Python service.
 *
 * One sample is produced per critical element that has both a retrieval
 * context and an LLM-generated explanation — those are the only triples
 * with the (query, contexts, response) shape Ragas needs.
 */
@Service
class RagasEvaluationService(
    private val ragasApiClient: RagasApiClient
) {

    private val log = KotlinLogging.logger {}

    suspend fun scoreTestCase(
        analysisResponse: AnalysisResponse,
        bpmnElements: Set<BpmnElement>
    ): RagMetrics? {
        // Use the exact deduplicated pool the analyzer's prompt was built from, so Faithfulness
        // measures grounding against what the LLM actually saw (not the per-element display view).
        val promptContexts = analysisResponse.ragPromptContext ?: return null
        if (promptContexts.isEmpty()) return null

        val elementsById = bpmnElements.associateBy { it.id }
        val criticalById = analysisResponse.criticalElements.associateBy { it.id }
        val ragContextById = analysisResponse.ragContext.orEmpty()

        val samples = criticalById.mapNotNull { (elementId, critical) ->
            if (critical.reason.isBlank()) return@mapNotNull null
            val element = elementsById[elementId]
            val ctx = ragContextById[elementId]

            RagasSampleRequest(
                id = elementId,
                query = buildQuery(element, ctx),
                contexts = promptContexts,
                response = critical.reason
            )
        }

        if (samples.isEmpty()) {
            log.info { "No scorable RAG samples for this test case (no critical elements with both context and explanation)." }
            return null
        }

        samples.forEachIndexed { idx, s ->
            log.info {
                buildString {
                    appendLine("=== RAGAS SAMPLE AUDIT [${idx + 1}/${samples.size}] ===")
                    appendLine("id: ${s.id}")
                    appendLine("query: ${s.query}")
                    appendLine("response (FULL):")
                    appendLine(s.response)
                    appendLine("contexts (${s.contexts.size}, FULL):")
                    s.contexts.forEachIndexed { i, c -> appendLine("  [$i] $c") }
                    appendLine("=== END RAGAS SAMPLE AUDIT [${idx + 1}/${samples.size}] ===")
                }
            }
        }

        return try {
            val response = ragasApiClient.evaluate(
                RagasEvaluationRequest(
                    samples = samples,
                    metrics = listOf("faithfulness", "context_utilization")
                )
            )
            RagMetrics(
                faithfulness = response.aggregate.faithfulnessMean,
                contextUtilization = response.aggregate.contextUtilizationMean,
                sampleCount = response.aggregate.sampleCount,
                failedCount = response.aggregate.failedCount
            )
        } catch (e: Exception) {
            log.warn(e) { "Ragas evaluation failed; classification metrics are unaffected." }
            null
        }
    }

    private fun buildQuery(element: BpmnElement?, ctx: RagElementContext?): String {
        // Phrase as a question so Ragas' ResponseRelevancy (which generates synthetic
        // questions from the response and embeds them) has a comparable user_input.
        val name = element?.name ?: ctx?.activityName ?: "this BPMN activity"
        val attrs = listOfNotNull(
            element?.documentation?.takeIf { it.isNotBlank() }?.let { "documentation: $it" },
            element?.poolName?.takeIf { it.isNotBlank() }?.let { "pool: $it" },
            element?.laneName?.takeIf { it.isNotBlank() }?.let { "lane: $it" }
        )
        val suffix = if (attrs.isEmpty()) "" else " (${attrs.joinToString(", ")})"
        return "Is the BPMN activity '$name'$suffix GDPR-critical, and if so, why?"
    }

}
