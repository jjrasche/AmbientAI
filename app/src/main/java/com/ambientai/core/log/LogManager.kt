package com.ambientai.core.log

import com.ambientai.data.entities.LogEntry
import com.ambientai.data.repositories.LogEntryRepository
import com.ambientai.data.repositories.TranscriptRepository
import org.json.JSONObject

class LogManager {
    private val repo = LogEntryRepository()
    private val transcriptRepo = TranscriptRepository()

    fun execute(actionName: String, input: JSONObject): JSONObject = when (actionName) {
        "log.write" -> write(input)
        "log.query" -> query(input)
        else -> throw Exception("Unknown action: $actionName")
    }

    private fun write(input: JSONObject): JSONObject {
        val type = input.optString("type", null) ?: throw Exception("Missing required field: type")
        val data = input.optJSONObject("data") ?: throw Exception("Missing required field: data")
        val transcriptId = input.optLong("transcriptId", -1).takeIf { it != -1L } ?: throw Exception("Missing required field: transcriptId")
        val transcript = transcriptRepo.getById(transcriptId) ?: throw Exception("Transcript $transcriptId not found")
        return LogEntry(type = type, data = data.toString(), timestamp = System.currentTimeMillis()).also {
            it.transcript.target = transcript
            repo.save(it)
        }.let { JSONObject(mapOf("id" to it.id, "type" to it.type, "transcriptId" to transcriptId)) }
    }
    private fun query(input: JSONObject): JSONObject {
        val type = input.optString("type", null)
        val results = (if (type != null) repo.getByType(type) else repo.getAll()).map {
            mapOf("id" to it.id, "type" to it.type, "data" to JSONObject(it.data), "timestamp" to it.timestamp, "transcriptId" to it.transcript.targetId)
        }
        return JSONObject(mapOf("count" to results.size, "entries" to results))
    }
}