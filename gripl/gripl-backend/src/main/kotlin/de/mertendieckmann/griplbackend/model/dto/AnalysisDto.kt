package de.mertendieckmann.griplbackend.model.dto

import de.mertendieckmann.griplbackend.application.BpmnExtractor
import de.mertendieckmann.griplbackend.model.BpmnElement
import de.mertendieckmann.griplbackend.model.analysis.BpmnAnalysisResult

data class AnalysisResponse(
    val criticalElements: List<CriticalElement>,
    val amountOfRetries: Int? = null,
    val ragContext: Map<String, RagElementContext>? = null
) {
    data class CriticalElement(
        val id: String,
        val name: String?,
        val reason: String
    )

    companion object {
        fun fromBpmnAnalysisResult(
            result: BpmnAnalysisResult,
            bpmnElements: Set<BpmnElement>,
            amountOfRetries: Int,
            ragContext: Map<String, RagElementContext>? = null
        ): AnalysisResponse {
            val elements = result.elements.map { element ->
                CriticalElement(
                    id = element.id,
                    name = bpmnElements.find { it.id == element.id }?.name,
                    reason = element.reason
                )
            }

            return AnalysisResponse(
                criticalElements = elements,
                amountOfRetries = amountOfRetries,
                ragContext = ragContext
            )
        }

        fun fromBpmnAnalysisResult(result: BpmnAnalysisResult, bpmnXml: String, amountOfRetries: Int): AnalysisResponse {
            val extractor = BpmnExtractor()
            val bpmnElements = extractor.extractBpmnElements(bpmnXml)
            return fromBpmnAnalysisResult(result, bpmnElements, amountOfRetries)
        }
    }
}

data class RagElementContext(
    val activityName: String?,
    val entities: List<RagEntity>,
    val relationships: List<RagRelationship>,
    val documents: List<RagDocument>
)

data class RagEntity(
    val label: String,
    val type: String,
    val description: String
)

data class RagRelationship(
    val source: String,
    val target: String,
    val label: String
)

data class RagDocument(
    val content: String,
    val preview: String
)