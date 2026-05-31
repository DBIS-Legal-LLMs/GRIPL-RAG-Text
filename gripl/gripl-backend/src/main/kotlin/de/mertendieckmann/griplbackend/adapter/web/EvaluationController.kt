package de.mertendieckmann.griplbackend.adapter.web

import de.mertendieckmann.griplbackend.adapter.web.utils.ControllerUtils
import de.mertendieckmann.griplbackend.evaluation.MultiEvaluationRunner
import de.mertendieckmann.griplbackend.model.dto.EvaluationReportStepInfo
import de.mertendieckmann.griplbackend.model.dto.ModelReportEnvelope
import de.mertendieckmann.griplbackend.model.dto.MultiEvaluationRequest
import de.mertendieckmann.griplbackend.repository.EvaluationRunRepository
import de.mertendieckmann.griplbackend.repository.EvaluationRunRow
import io.swagger.v3.oas.annotations.Operation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.withContext
import org.springframework.core.env.Environment
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/gdpr/evaluation")
@CrossOrigin(
    origins = ["\${app.frontend.base-url}"],
    allowCredentials = "true",
    allowedHeaders = ["*"],
    methods = [
        RequestMethod.GET,
        RequestMethod.POST,
        RequestMethod.DELETE,
    ]
)
class EvaluationController(
    private val multiEvaluationRunner: MultiEvaluationRunner,
    private val evaluationRunRepository: EvaluationRunRepository,
    private val env: Environment
) {

    @Operation(summary = "Evaluates the classification algorithm against the dataset (markdown)")
    @PostMapping("/markdown", produces = [MediaType.TEXT_MARKDOWN_VALUE])
    suspend fun evaluate(@RequestBody request: MultiEvaluationRequest): String {
        val sb = StringBuilder()
        var currentLabel: String? = null
        val resolvedRequest = ControllerUtils.resolveEnvironmentVariables(request, env)
            ?: throw IllegalArgumentException("Invalid request after resolving environment variables.")

        multiEvaluationRunner.runAll(resolvedRequest).collect { envelope ->
            val (label, report) = envelope

            if (currentLabel != label) {
                if (currentLabel != null) sb.appendLine()
                sb.appendLine("# Modell: $label").appendLine()
                currentLabel = label
            }

            if (report !is EvaluationReportStepInfo) {
                val md = report.toMarkdown()
                if (md.isNotBlank()) {
                    sb.appendLine(md).appendLine()
                }
            }
        }

        return sb.toString().trimEnd()
    }

    @Operation(summary = "Evaluates the classification algorithm against the dataset (NDJSON stream)")
    @PostMapping("/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    suspend fun evaluateStream(@RequestBody request: MultiEvaluationRequest): Flow<ModelReportEnvelope> {
        val resolvedRequest = ControllerUtils.resolveEnvironmentVariables(request, env)
            ?: throw IllegalArgumentException("Invalid request after resolving environment variables.")
        return multiEvaluationRunner.runAll(resolvedRequest)
    }

    // ── Saved-run endpoints ───────────────────────────────────────────────────

    @Operation(summary = "List all saved evaluation runs")
    @GetMapping("/runs")
    suspend fun listRuns(): List<EvaluationRunRow> =
        withContext(Dispatchers.IO) { evaluationRunRepository.listRuns() }

    @Operation(summary = "Replay a saved run as an NDJSON stream (same format as /stream)")
    @GetMapping("/runs/{id}/stream", produces = [MediaType.APPLICATION_NDJSON_VALUE])
    suspend fun replayRun(@PathVariable id: Long): Flow<ModelReportEnvelope> {
        val events = withContext(Dispatchers.IO) { evaluationRunRepository.getEvents(id) }
        return events.asFlow()
    }

    @Operation(summary = "Delete a saved evaluation run")
    @DeleteMapping("/runs/{id}")
    suspend fun deleteRun(@PathVariable id: Long) {
        withContext(Dispatchers.IO) { evaluationRunRepository.deleteRun(id) }
    }
}
