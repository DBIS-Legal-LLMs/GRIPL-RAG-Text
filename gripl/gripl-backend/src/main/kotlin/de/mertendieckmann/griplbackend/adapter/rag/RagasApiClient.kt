package de.mertendieckmann.griplbackend.adapter.rag

import de.mertendieckmann.griplbackend.config.RagApiProperties
import de.mertendieckmann.griplbackend.model.dto.RagasEvaluationRequest
import de.mertendieckmann.griplbackend.model.dto.RagasEvaluationResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.reactor.awaitSingle
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration


@Service
class RagasApiClient(
    private val webClientBuilder: WebClient.Builder,
    private val ragApiProperties: RagApiProperties
) {

    private val log = KotlinLogging.logger {}

    private val evaluationUrl: String by lazy { deriveEvaluationUrl(ragApiProperties.url) }
    private val webClient: WebClient by lazy { webClientBuilder.build() }

    suspend fun evaluate(request: RagasEvaluationRequest): RagasEvaluationResponse {
        log.info { "Submitting ${request.samples.size} sample(s) to Ragas evaluator at $evaluationUrl" }

        return try {
            webClient.post()
                .uri(evaluationUrl)
                .bodyValue(request)
                .retrieve()
                .bodyToMono(RagasEvaluationResponse::class.java)
                .timeout(Duration.ofMinutes(15))
                .awaitSingle()
        } catch (e: Exception) {
            log.error(e) { "Ragas evaluation call failed" }
            throw RuntimeException("Failed to call Ragas evaluator at $evaluationUrl", e)
        }
    }

    private fun deriveEvaluationUrl(queryUrl: String): String {
        val trimmed = queryUrl.trimEnd('/')
        // Replace a trailing /query (or /api/query) with /evaluate/ragas under the same /api.
        return when {
            trimmed.endsWith("/api/query") -> trimmed.removeSuffix("/query") + "/evaluate/ragas"
            trimmed.endsWith("/query") -> trimmed.removeSuffix("/query") + "/evaluate/ragas"
            trimmed.endsWith("/api") -> "$trimmed/evaluate/ragas"
            else -> "$trimmed/api/evaluate/ragas"
        }
    }
}
