package com.ambientai.core.log

import com.ambientai.data.entities.LogEntry
import com.ambientai.data.repositories.ILogEntryRepository
import com.ambientai.data.repositories.ITranscriptRepository
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LogManager @Inject constructor(
    private val repo: ILogEntryRepository,
    private val transcriptRepo: ITranscriptRepository
) {

    fun execute(actionName: String, input: JSONObject): JSONObject = when (actionName) {
        "log.write" -> write(input)
        "log.query" -> query(input)
        else -> throw Exception("Unknown action: $actionName")
    }

    private fun write(input: JSONObject): JSONObject = (input.optString("type", null) ?: throw Exception("Missing required field: type")).let { type ->
        (input.optJSONObject("data") ?: throw Exception("Missing required field: data")).let { data ->
            (input.optLong("transcriptId", -1).takeIf { it != -1L } ?: throw Exception("Missing required field: transcriptId")).let { transcriptId ->
                (transcriptRepo.getById(transcriptId) ?: throw Exception("Transcript $transcriptId not found")).let { transcript ->
                    LogEntry(type = type, data = data.toString(), timestamp = System.currentTimeMillis()).also {
                        it.transcript.target = transcript
                        repo.save(it)
                    }.let { JSONObject(mapOf("id" to it.id, "type" to it.type, "transcriptId" to transcriptId)) }
                }
            }
        }
    }
    private fun query(input: JSONObject): JSONObject = input.optString("type", null).let { type ->
        (if (type != null) repo.getByType(type) else repo.getAll()).map {
            mapOf("id" to it.id, "type" to it.type, "data" to JSONObject(it.data), "timestamp" to it.timestamp, "transcriptId" to it.transcript.targetId)
        }.let { results -> JSONObject(mapOf("count" to results.size, "entries" to results)) }
    }
}