package de.mertendieckmann.griplbackend.repository

import com.fasterxml.jackson.databind.ObjectMapper
import de.mertendieckmann.griplbackend.model.dto.ModelReportEnvelope
import org.postgresql.util.PGobject
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Repository
import java.sql.Statement
import java.sql.Timestamp
import java.time.Instant

data class EvaluationRunRow(
    val id: Long,
    val createdAt: Instant,
    val completedAt: Instant?,
    val status: String
)

@Repository
class EvaluationRunRepository(
    private val jdbc: JdbcTemplate,
    private val objectMapper: ObjectMapper
) {

    fun createRun(): Long {
        val keyHolder = GeneratedKeyHolder()
        jdbc.update({ con ->
            con.prepareStatement(
                "INSERT INTO evaluation_run (status) VALUES ('in_progress')",
                Statement.RETURN_GENERATED_KEYS
            )
        }, keyHolder)
        return (keyHolder.keys!!["id"] as Number).toLong()
    }

    fun saveEvent(runId: Long, envelope: ModelReportEnvelope) {
        val json = objectMapper.writeValueAsString(envelope)
        val pgJson = PGobject().apply { type = "jsonb"; value = json }
        jdbc.update("INSERT INTO evaluation_run_event (run_id, payload) VALUES (?, ?)", runId, pgJson)
    }

    fun completeRun(runId: Long) {
        jdbc.update(
            "UPDATE evaluation_run SET status = 'completed', completed_at = ? WHERE id = ?",
            Timestamp.from(Instant.now()), runId
        )
    }

    fun failRun(runId: Long) {
        jdbc.update(
            "UPDATE evaluation_run SET status = 'failed', completed_at = ? WHERE id = ?",
            Timestamp.from(Instant.now()), runId
        )
    }

    fun listRuns(): List<EvaluationRunRow> =
        jdbc.query("SELECT id, created_at, completed_at, status FROM evaluation_run ORDER BY id DESC") { rs, _ ->
            EvaluationRunRow(
                id = rs.getLong("id"),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                completedAt = rs.getTimestamp("completed_at")?.toInstant(),
                status = rs.getString("status")
            )
        }

    fun getEvents(runId: Long): List<ModelReportEnvelope> =
        jdbc.query(
            "SELECT payload FROM evaluation_run_event WHERE run_id = ? ORDER BY id",
            { rs, _ -> objectMapper.readValue(rs.getString("payload"), ModelReportEnvelope::class.java) },
            runId
        )

    fun deleteRun(runId: Long): Int =
        jdbc.update("DELETE FROM evaluation_run WHERE id = ?", runId)
}
