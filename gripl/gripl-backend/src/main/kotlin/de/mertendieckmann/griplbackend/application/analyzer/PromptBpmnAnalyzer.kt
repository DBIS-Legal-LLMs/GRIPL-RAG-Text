package de.mertendieckmann.griplbackend.application.analyzer

import de.mertendieckmann.griplbackend.ai.PromptBpmnAnalysisAiServiceFactory
import de.mertendieckmann.griplbackend.ai.SharedChatMemoryProvider
import de.mertendieckmann.griplbackend.application.BpmnExtractor
import de.mertendieckmann.griplbackend.application.SafetyNet
import de.mertendieckmann.griplbackend.adapter.rag.RagApiClient
import de.mertendieckmann.griplbackend.model.dto.AnalysisResponse
import com.fasterxml.jackson.databind.ObjectMapper
import dev.langchain4j.model.chat.ChatModel
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.*

class PromptBpmnAnalyzer(
    llm: ChatModel,
    private val ragApiClient: RagApiClient
): BpmnAnalyzer {
    private val log = KotlinLogging.logger { }
    private val objectMapper = ObjectMapper()
    private val memoryProvider = SharedChatMemoryProvider(50)
    private val bpmnAnalysisAiService = PromptBpmnAnalysisAiServiceFactory.create(llm, memoryProvider)
    private val safetyNet = SafetyNet(llm, memoryProvider)

    override fun analyzeBpmnForGdpr(bpmnXml: String, useRag: Boolean, ragMode: String): AnalysisResponse {
        val sessionId = UUID.randomUUID().toString()

        val bpmnElements = BpmnExtractor().extractBpmnElements(bpmnXml)
        
        val ragContextMap = mutableMapOf<String, String>()

        if (useRag) {
            kotlinx.coroutines.runBlocking {
                bpmnElements.forEach { element ->
                    val queryText = sequenceOf(element.name, element.documentation, element.poolName, element.laneName)
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
        }

        val result = safetyNet.safeGuardAnalysisResultParsing(
            sessionId = sessionId,
            maxRetries = 3,
        ) {
            bpmnAnalysisAiService.analyze(sessionId, bpmnElements)
        }

        val analysisResult = result.first.resolveActivities(bpmnElements)
        val amountOfRetries = result.second

        log.info { "BPMN Analysis Result: $analysisResult" }

        return AnalysisResponse.fromBpmnAnalysisResult(analysisResult, bpmnElements, amountOfRetries)
    }
}