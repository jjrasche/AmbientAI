package com.ambientai.core.log

import android.content.Context
import com.ambientai.data.entities.LogEntry
import com.ambientai.data.repositories.LogRepository
import com.ambientai.data.repositories.TranscriptRepository
import org.json.JSONObject

/**
 * Manages log entry actions for workflows.
 * Handles writing and querying generic log entries.
 */
class LogManager() {
    private val repo = LogRepository()
    private val transcriptRepo = TranscriptRepository()

    fun execute(actionName: String, input: JSONObject): JSONObject {
        return when (actionName) {
            "log.write" -> write(input)
            "log.query" -> query(input)
            else -> throw Exception("Unknown action: $actionName")
        }
    }

    private fun write(input: JSONObject): JSONObject {
        val type = input.optString("type", null)
            ?: throw Exception("Missing required field: type")

        val data = input.optJSONObject("data")
            ?: throw Exception("Missing required field: data")

        val transcriptId = input.optLong("transcriptId", -1)
        if (transcriptId == -1L) {
            throw Exception("Missing required field: transcriptId")
        }

        // Verify transcript exists
        val transcript = transcriptRepo.getById(transcriptId)
            ?: throw Exception("Transcript $transcriptId not found")

        val entry = LogEntry(
            type = type,
            data = data.toString(),
            timestamp = System.currentTimeMillis()
        )
        entry.transcript.target = transcript
        repo.save(entry)
        return JSONObject(mapOf("id" to entry.id, "type" to entry.type, "transcriptId" to transcriptId))
    }
    private fun query(input: JSONObject): JSONObject {
        val type = input.optString("type", null)
        val entries = if (type != null) {
            repo.getByType(type)
        } else {
            repo.getAll()
        }
        val results = entries.map { entry -> mapOf(
            "id" to entry.id,
            "type" to entry.type,
            "data" to JSONObject(entry.data),
            "timestamp" to entry.timestamp,
            "transcriptId" to entry.transcript.targetId
        )}
        return JSONObject(mapOf("count" to results.size, "entries" to results))
    }
}