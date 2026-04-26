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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.*

class PromptBpmnAnalyzer(
    llm: ChatModel,
    private val ragApiClient: RagApiClient
) : BpmnAnalyzer {

    private val log = KotlinLogging.logger { }
    private val objectMapper = ObjectMapper()
    private val memoryProvider = SharedChatMemoryProvider(50)
    private val bpmnAnalysisAiServiceWithRag = PromptBpmnAnalysisAiServiceFactory.create(llm, memoryProvider)
    private val bpmnAnalysisAiServiceNoRag = PromptBpmnAnalysisAiServiceFactory.createWithoutRag(llm, memoryProvider)
    private val safetyNet = SafetyNet(llm, memoryProvider)

    override fun analyzeBpmnForGdpr(bpmnXml: String, useRag: Boolean, ragMode: String): AnalysisResponse {
        val sessionId = UUID.randomUUID().toString()

        val bpmnElements = BpmnExtractor().extractBpmnElements(bpmnXml)

        if (useRag) {
            // RAG-augmented path
            val ragContextMap = fetchRagContext(bpmnElements, ragMode)

            val result = safetyNet.safeGuardAnalysisResultParsing(sessionId, maxRetries = 3) {
                val formattedPrompt = buildCombinedPrompt(bpmnElements, ragContextMap)
                bpmnAnalysisAiServiceWithRag.analyzeWithRagContext(sessionId, formattedPrompt)
            }

            val analysisResult = result.first.resolveActivities(bpmnElements)
            val amountOfRetries = result.second
            val ragContext = parseRagContextForResponse(ragContextMap, bpmnElements)

            log.info { "BPMN Analysis Result (with RAG): $analysisResult" }

            return AnalysisResponse.fromBpmnAnalysisResult(analysisResult, bpmnElements, amountOfRetries, ragContext)
        } else {
            // Original path — unchanged from evaluation baseline
            val result = safetyNet.safeGuardAnalysisResultParsing(sessionId, maxRetries = 3) {
                bpmnAnalysisAiServiceNoRag.analyze(sessionId, bpmnElements)
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
        ragMode: String,
        maxConcurrency: Int = 8
    ): Map<String, String> {
        val semaphore = Semaphore(maxConcurrency)

        val result = kotlinx.coroutines.runBlocking {
            bpmnElements
                .map { element ->
                    async {
                        val queryText = sequenceOf(
                            element.name, element.documentation, element.poolName, element.laneName
                        )
                            .filterNotNull()
                            .filter { it.isNotBlank() }
                            .joinToString(" - ")

                        if (queryText.isNotBlank()) {
                            try {
                                semaphore.withPermit {
                                    val response = ragApiClient.queryRag(queryText, ragMode)
                                    element.id to objectMapper.writeValueAsString(response.response)
                                }
                            } catch (e: Exception) {
                                log.error(e) { "RAG query failed for element ${element.id}" }
                                null
                            }
                        } else null
                    }
                }
                .awaitAll()
                .filterNotNull()
                .toMap()
        }

        log.info { "RAG context retrieved for ${result.size} / ${bpmnElements.size} elements" }
        return result
    }

    // -------------------------------------------------------------------------
    // LLM prompt builder
    // -------------------------------------------------------------------------

    /**
     * Combines the RAG-retrieved legal context with the BPMN elements into a single prompt.
     * Context is deduplicated across all elements to avoid repeating the same GDPR
     * entities/documents multiple times, which would bloat the token count unnecessarily.
     */
    private fun buildCombinedPrompt(
        bpmnElements: Set<BpmnElement>,
        ragContextMap: Map<String, String>
    ): String = buildString {
        // Deduplicated pools across all elements
        val seenEntityLabels = mutableSetOf<String>()
        val seenRelKeys = mutableSetOf<String>()
        val seenDocIds = mutableSetOf<String>()

        val allEntities = mutableListOf<Map<String, Any>>()
        val allRels = mutableListOf<Map<String, Any>>()
        val allDocs = mutableListOf<Map<String, Any>>()

        ragContextMap.values.forEach { rawJson ->
            try {
                val ctx = objectMapper.readValue<Map<String, Any>>(rawJson)

                @Suppress("UNCHECKED_CAST")
                (ctx["entities"] as? List<Map<String, Any>> ?: emptyList()).forEach eachEntity@{ e ->
                    val label = (e["label"] as? String) ?: return@eachEntity
                    if (seenEntityLabels.add(label)) allEntities += e
                }

                @Suppress("UNCHECKED_CAST")
                (ctx["relationships"] as? List<Map<String, Any>> ?: emptyList()).forEach { r ->
                    val key = "${r["source_label"]}|${r["label"]}|${r["target_label"]}"
                    if (seenRelKeys.add(key)) allRels += r
                }

                @Suppress("UNCHECKED_CAST")
                (ctx["documents"] as? List<Map<String, Any>> ?: emptyList()).forEach eachDoc@{ d ->
                    val docId = (d["reference_id"] as? String) ?: (d["preview"] as? String) ?: return@eachDoc
                    if (seenDocIds.add(docId)) allDocs += d
                }
            } catch (e: Exception) {
                log.warn { "Failed to parse RAG context JSON: ${e.message}" }
            }
        }

        appendLine("=== RETRIEVED GDPR KNOWLEDGE ===")
        appendLine(
            "The following unique knowledge was retrieved from a GDPR legal knowledge graph. " +
            "Use this context when assessing whether each BPMN activity processes personal data."
        )
        appendLine()

        if (allEntities.isNotEmpty()) {
            appendLine("Relevant GDPR Entities:")
            allEntities.take(20).forEach { e ->
                val label = e["label"] ?: ""
                val type  = e["type"]  ?: ""
                val desc  = (e["description_text"] as? String)?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""
                appendLine("  • [$type] $label$desc")
            }
            appendLine()
        }

        if (allRels.isNotEmpty()) {
            appendLine("Key Relationships:")
            allRels.take(15).forEach { r ->
                val src   = r["source_label"] ?: ""
                val tgt   = r["target_label"] ?: ""
                val label = (r["label"] as? String)?.takeIf { it.isNotBlank() } ?: "relates to"
                appendLine("  • $src → $tgt ($label)")
            }
            appendLine()
        }

        if (allDocs.isNotEmpty()) {
            appendLine("Supporting Legal Excerpts:")
            allDocs.take(5).forEach { d ->
                val preview = d["preview"] ?: d["content"] ?: ""
                val sourceDoc = d["reference_id"] as? String
                if (sourceDoc != null) {
                    appendLine("  [Source: $sourceDoc] \"$preview\"")
                } else {
                    appendLine("  \"$preview\"")
                }
            }
            appendLine()
        }

        if (allEntities.isEmpty() && allRels.isEmpty() && allDocs.isEmpty()) {
            appendLine("  (No GDPR context retrieved.)")
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