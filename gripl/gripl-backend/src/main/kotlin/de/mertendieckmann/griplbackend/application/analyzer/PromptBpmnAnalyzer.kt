package de.mertendieckmann.griplbackend.application.analyzer

import de.mertendieckmann.griplbackend.ai.PromptBpmnAnalysisAiServiceFactory
import de.mertendieckmann.griplbackend.ai.SharedChatMemoryProvider
import de.mertendieckmann.griplbackend.application.BpmnExtractor
import de.mertendieckmann.griplbackend.application.SafetyNet
import de.mertendieckmann.griplbackend.adapter.rag.RagApiClient
import de.mertendieckmann.griplbackend.model.BpmnElement
import de.mertendieckmann.griplbackend.model.dto.AnalysisResponse
import de.mertendieckmann.griplbackend.model.dto.RagDocument
import de.mertendieckmann.griplbackend.model.dto.RagElementContext
import de.mertendieckmann.griplbackend.model.dto.RagEntity
import de.mertendieckmann.griplbackend.model.dto.RagRelationship
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import dev.langchain4j.model.chat.ChatModel
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.*

class PromptBpmnAnalyzer(
    llm: ChatModel,
    private val ragApiClient: RagApiClient
) : BpmnAnalyzer {

    private val log = KotlinLogging.logger { }
    private val objectMapper = ObjectMapper()
    private val memoryProvider = SharedChatMemoryProvider(50)
    private val bpmnAnalysisAiService = PromptBpmnAnalysisAiServiceFactory.create(llm, memoryProvider)
    private val safetyNet = SafetyNet(llm, memoryProvider)

    override fun analyzeBpmnForGdpr(bpmnXml: String, useRag: Boolean, ragMode: String): AnalysisResponse {
        val sessionId = UUID.randomUUID().toString()

        val bpmnElements = BpmnExtractor().extractBpmnElements(bpmnXml)

        if (useRag) {
            // RAG-augmented path
            val ragContextMap = fetchRagContext(bpmnElements, ragMode)

            val result = safetyNet.safeGuardAnalysisResultParsing(sessionId, maxRetries = 3) {
                val formattedPrompt = buildCombinedPrompt(bpmnElements, ragContextMap)
                bpmnAnalysisAiService.analyzeWithRagContext(sessionId, formattedPrompt)
            }

            val analysisResult = result.first.resolveActivities(bpmnElements)
            val amountOfRetries = result.second
            val ragContext = parseRagContextForResponse(ragContextMap, bpmnElements)

            log.info { "BPMN Analysis Result (with RAG): $analysisResult" }

            return AnalysisResponse.fromBpmnAnalysisResult(analysisResult, bpmnElements, amountOfRetries, ragContext)
        } else {
            // Original path — unchanged from evaluation baseline
            val result = safetyNet.safeGuardAnalysisResultParsing(sessionId, maxRetries = 3) {
                bpmnAnalysisAiService.analyze(sessionId, bpmnElements)
            }

            val analysisResult = result.first.resolveActivities(bpmnElements)
            val amountOfRetries = result.second

            log.info { "BPMN Analysis Result: $analysisResult" }

            return AnalysisResponse.fromBpmnAnalysisResult(analysisResult, bpmnElements, amountOfRetries)
        }
    }

    // -------------------------------------------------------------------------
    // RAG context retrieval
    // -------------------------------------------------------------------------

    private fun fetchRagContext(
        bpmnElements: Set<BpmnElement>,
        ragMode: String
    ): Map<String, String> {
        val ragContextMap = mutableMapOf<String, String>()

        kotlinx.coroutines.runBlocking {
            bpmnElements.forEach { element ->
                val queryText = sequenceOf(
                    element.name, element.documentation, element.poolName, element.laneName
                )
                    .filterNotNull()
                    .filter { it.isNotBlank() }
                    .joinToString(" - ")

                if (queryText.isNotBlank()) {
                    try {
                        val response = ragApiClient.queryRag(queryText, ragMode)
                        ragContextMap[element.id] = objectMapper.writeValueAsString(response.response)
                    } catch (e: Exception) {
                        log.error(e) { "RAG query failed for element ${element.id}" }
                    }
                }
            }
        }

        log.info { "RAG context retrieved for ${ragContextMap.size} / ${bpmnElements.size} elements" }
        return ragContextMap
    }

    // -------------------------------------------------------------------------
    // LLM prompt builder
    // -------------------------------------------------------------------------

    /**
     * Combines the RAG-retrieved legal context with the original BPMN element
     * representation (Set.toString()) into a single user message.
     *
     * The BPMN section uses the same serialisation LangChain4j applied in the
     * no-RAG path so the model sees identical element data in both modes.
     */
    private fun buildCombinedPrompt(
        bpmnElements: Set<BpmnElement>,
        ragContextMap: Map<String, String>
    ): String = buildString {
        appendLine("=== RETRIEVED GDPR KNOWLEDGE ===")
        appendLine(
            "The following knowledge was retrieved from a GDPR legal knowledge graph. " +
            "Each section maps to a BPMN activity. Use this context when assessing " +
            "whether each activity processes personal data."
        )
        appendLine()

        val elementById = bpmnElements.associateBy { it.id }
        ragContextMap.entries.forEachIndexed { index, (elementId, rawJson) ->
            val activityLabel = elementById[elementId]?.name?.takeIf { it.isNotBlank() } ?: elementId
            appendLine("--- Activity ${index + 1}: \"$activityLabel\" ---")

            try {
                val ctx = objectMapper.readValue<Map<String, Any>>(rawJson)

                @Suppress("UNCHECKED_CAST")
                val entities = ctx["entities"] as? List<Map<String, Any>> ?: emptyList()
                if (entities.isNotEmpty()) {
                    appendLine("Relevant GDPR Entities:")
                    entities.take(10).forEach { e ->
                        val label = e["label"] ?: ""
                        val type  = e["type"]  ?: ""
                        val desc  = (e["description_text"] as? String)
                            ?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
                        appendLine("  • [$type] $label$desc")
                    }
                }

                @Suppress("UNCHECKED_CAST")
                val rels = ctx["relationships"] as? List<Map<String, Any>> ?: emptyList()
                if (rels.isNotEmpty()) {
                    appendLine("Key Relationships:")
                    rels.take(8).forEach { r ->
                        val src   = r["source_label"] ?: ""
                        val tgt   = r["target_label"] ?: ""
                        val label = (r["label"] as? String)?.takeIf { it.isNotBlank() } ?: "relates to"
                        appendLine("  • $src → $tgt ($label)")
                    }
                }

                @Suppress("UNCHECKED_CAST")
                val docs = ctx["documents"] as? List<Map<String, Any>> ?: emptyList()
                if (docs.isNotEmpty()) {
                    appendLine("Supporting Legal Excerpts:")
                    docs.take(3).forEach { d ->
                        val preview = d["preview"] ?: d["content"] ?: ""
                        appendLine("  \"$preview\"")
                    }
                }

                if (entities.isEmpty() && rels.isEmpty() && docs.isEmpty()) {
                    appendLine("  (No specific context retrieved for this activity.)")
                }

            } catch (e: Exception) {
                log.warn { "Failed to parse RAG context JSON for element $elementId: ${e.message}" }
                appendLine("  (RAG context unavailable for this activity.)")
            }

            appendLine()
        }

        appendLine("=== BPMN PROCESS ELEMENTS ===")
        append(bpmnElements.toString())
    }

    // -------------------------------------------------------------------------
    // RAG context → frontend DTO
    // -------------------------------------------------------------------------

    /**
     * Parses the raw JSON context map into typed [RagElementContext] objects
     * for inclusion in the API response to the frontend.
     */
    private fun parseRagContextForResponse(
        ragContextMap: Map<String, String>,
        bpmnElements: Set<BpmnElement>
    ): Map<String, RagElementContext> {
        val elementById = bpmnElements.associateBy { it.id }
        val result = mutableMapOf<String, RagElementContext>()

        ragContextMap.forEach { (elementId, rawJson) ->
            try {
                val ctx = objectMapper.readValue<Map<String, Any>>(rawJson)

                @Suppress("UNCHECKED_CAST")
                val entities = (ctx["entities"] as? List<Map<String, Any>> ?: emptyList()).map { e ->
                    RagEntity(
                        label = (e["label"] as? String) ?: "",
                        type = (e["type"] as? String) ?: "Unknown",
                        description = (e["description_text"] as? String) ?: ""
                    )
                }

                @Suppress("UNCHECKED_CAST")
                val relationships = (ctx["relationships"] as? List<Map<String, Any>> ?: emptyList()).map { r ->
                    RagRelationship(
                        source = (r["source_label"] as? String) ?: "",
                        target = (r["target_label"] as? String) ?: "",
                        label = (r["label"] as? String) ?: "relates to"
                    )
                }

                @Suppress("UNCHECKED_CAST")
                val documents = (ctx["documents"] as? List<Map<String, Any>> ?: emptyList()).map { d ->
                    RagDocument(
                        content = (d["content"] as? String) ?: "",
                        preview = (d["preview"] as? String) ?: (d["content"] as? String) ?: "",
                        sourceDocument = (d["reference_id"] as? String)
                    )
                }

                result[elementId] = RagElementContext(
                    activityName = elementById[elementId]?.name,
                    entities = entities,
                    relationships = relationships,
                    documents = documents
                )
            } catch (e: Exception) {
                log.warn { "Failed to parse RAG context for element $elementId: ${e.message}" }
            }
        }

        return result
    }
}