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
        val ragContext = analysisResponse.ragContext ?: return null
        if (ragContext.isEmpty()) return null

        val elementsById = bpmnElements.associateBy { it.id }
        val criticalById = analysisResponse.criticalElements.associateBy { it.id }

        val samples = criticalById.mapNotNull { (elementId, critical) ->
            val ctx = ragContext[elementId] ?: return@mapNotNull null
            val element = elementsById[elementId]
            val query = buildQuery(element, ctx)
            val contexts = buildContextStrings(ctx)
            if (contexts.isEmpty() || critical.reason.isBlank()) return@mapNotNull null

            RagasSampleRequest(
                id = elementId,
                query = query,
                contexts = contexts,
                response = critical.reason
            )
        }

        if (samples.isEmpty()) {
            log.info { "No scorable RAG samples for this test case (no critical elements with both context and explanation)." }
            return null
        }

        return try {
            val response = ragasApiClient.evaluate(RagasEvaluationRequest(samples = samples))
            RagMetrics(
                faithfulness = response.aggregate.faithfulnessMean,
                answerRelevancy = response.aggregate.answerRelevancyMean,
                sampleCount = response.aggregate.sampleCount,
                failedCount = response.aggregate.failedCount
            )
        } catch (e: Exception) {
            log.warn(e) { "Ragas evaluation failed; classification metrics are unaffected." }
            null
        }
    }

    private fun buildQuery(element: BpmnElement?, ctx: RagElementContext): String {
        // Mirror what PromptBpmnAnalyzer sends to the RAG service: name + doc + pool + lane.
        val parts = if (element != null) {
            sequenceOf(element.name, element.documentation, element.poolName, element.laneName)
        } else {
            sequenceOf(ctx.activityName)
        }
        return parts.filterNotNull().filter { it.isNotBlank() }.joinToString(" - ")
            .ifBlank { ctx.activityName ?: "" }
    }

    private fun buildContextStrings(ctx: RagElementContext): List<String> {
        val out = mutableListOf<String>()
        ctx.entities.forEach { e ->
            val desc = e.description.ifBlank { "" }
            val line = listOf("[${e.type}] ${e.label}", desc).filter { it.isNotBlank() }.joinToString(" — ")
            if (line.isNotBlank()) out += line
        }
        ctx.relationships.forEach { r ->
            val line = "${r.source} → ${r.target} (${r.label.ifBlank { "relates to" }})"
            out += line
        }
        ctx.documents.forEach { d ->
            val text = d.content.ifBlank { d.preview }
            if (text.isNotBlank()) out += text
        }
        return out
    }
}
