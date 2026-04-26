package de.mertendieckmann.griplbackend.ai

import dev.langchain4j.memory.chat.ChatMemoryProvider
import dev.langchain4j.model.chat.ChatModel
import dev.langchain4j.service.AiServices

object PromptBpmnAnalysisAiServiceFactory {

    private val basePrompt: String = loadResource("prompts/system-prompt-base.txt")
    private val citationsPrompt: String = loadResource("prompts/system-prompt-citations.txt")

    fun create(llm: ChatModel, memoryProvider: ChatMemoryProvider): PromptBpmnAnalysisAiService =
        buildService(llm, memoryProvider, withCitations = true)

    fun createWithoutRag(llm: ChatModel, memoryProvider: ChatMemoryProvider): PromptBpmnAnalysisAiService =
        buildService(llm, memoryProvider, withCitations = false)

    private fun buildService(
        llm: ChatModel,
        memoryProvider: ChatMemoryProvider,
        withCitations: Boolean
    ): PromptBpmnAnalysisAiService {
        val systemPrompt = if (withCitations) "$basePrompt\n\n$citationsPrompt" else basePrompt
        return AiServices
            .builder(PromptBpmnAnalysisAiService::class.java)
            .chatModel(llm)
            .chatMemoryProvider(memoryProvider)
            .systemMessageProvider { _ -> systemPrompt }
            .build()
    }

    private fun loadResource(path: String): String =
        checkNotNull(
            PromptBpmnAnalysisAiServiceFactory::class.java.classLoader.getResourceAsStream(path)
        ) { "Prompt resource not found: $path" }
            .bufferedReader()
            .readText()
            .trim()
}
