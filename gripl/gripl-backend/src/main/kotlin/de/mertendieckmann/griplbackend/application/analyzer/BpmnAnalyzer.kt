package de.mertendieckmann.griplbackend.application.analyzer

import de.mertendieckmann.griplbackend.model.dto.AnalysisResponse
import de.mertendieckmann.griplbackend.model.dto.RagMode

interface BpmnAnalyzer {

    fun analyzeBpmnForGdpr(bpmnXml: String, useRag: Boolean = false, ragMode: RagMode = RagMode.HYBRID): AnalysisResponse
}