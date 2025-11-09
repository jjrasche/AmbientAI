package com.ambientai.core.narrative

import com.ambientai.data.entities.Narrative
import com.ambientai.data.entities.NarrativeType
import com.ambientai.data.repositories.ActionExecutionRepository
import com.ambientai.data.repositories.NarrativeRepository
import com.ambientai.data.repositories.TaskRepository
import com.ambientai.data.repositories.TranscriptRepository
import com.ambientai.util.toHumanDuration
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

data class CurrentState(
    val timestamp: Long,
    val activeTask: String?,
    val pausedTasks: String,
    val recentConversation: String,
    val recentQueries: String
) {
    fun toJson() = JSONObject().apply {
        put("timestamp", timestamp)
        put("currentTime", SimpleDateFormat("h:mm a, EEEE MMM d", Locale.US).format(Date(timestamp)))
        put("activeTask", activeTask ?: "none")
        put("pausedTasks", pausedTasks)
        put("recentConversation", recentConversation)
        put("recentQueries", recentQueries)
    }.toString(2)
}

class NarrativeManager {
    private val narrativeRepo = NarrativeRepository()
    private val taskRepo = TaskRepository()
    private val transcriptRepo = TranscriptRepository()
    private val actionRepo = ActionExecutionRepository()

    fun execute(actionName: String, input: JSONObject) = when (actionName) {
        "state.gather" -> gatherState()
        "narrative.save" -> saveNarrative(input)
        else -> throw Exception("Unknown action: $actionName")
    }
    private fun gatherState(): JSONObject {
        val now = System.currentTimeMillis()
        val activeTask = try {
            taskRepo.getActive().let { "${it.name} (${it.totalElapsedMs().toHumanDuration()}, ${taskRepo.getSessionCount(it.id)} sessions)" }
        } catch (e: Exception) { null }
        val pausedTasks = taskRepo.getByStatus(com.ambientai.data.entities.TaskStatus.PAUSED).take(5)
            .joinToString(", ") { "${it.name} (${it.totalElapsedMs().toHumanDuration()})" }.ifEmpty { "none" }
        val recentConversation = transcriptRepo.getRecentContext(10).ifEmpty { "No recent conversation" }
        val recentQueries = actionRepo.getByActionName("llm.prompt").take(5).reversed()
            .mapNotNull { JSONObject(it.inputJson).optString("userPrompt", "").takeIf { p -> p.isNotEmpty() }?.let { p -> if (p.length >= 200) p.take(200) + "..." else p } }
            .joinToString("\n- ", prefix = "- ").ifEmpty { "No recent queries" }
        return JSONObject(mapOf("state" to CurrentState(now, activeTask, pausedTasks, recentConversation, recentQueries).toJson()))
    }
    private fun saveNarrative(input: JSONObject): JSONObject {
        val text = input.optString("text", null) ?: throw Exception("Missing required field: text")
        val stateSnapshot = input.optString("stateSnapshot", null) ?: throw Exception("Missing required field: stateSnapshot")
        if (text.isBlank()) throw Exception("Narrative text cannot be empty")
        val narrativeType = try { NarrativeType.valueOf(input.optString("narrativeType", "working").uppercase()) } catch (e: IllegalArgumentException) { NarrativeType.WORKING }
        return narrativeRepo.save(Narrative(text = text, narrativeType = narrativeType, stateSnapshot = stateSnapshot, timestamp = System.currentTimeMillis())).let {
            JSONObject(mapOf("id" to it.id, "narrativeType" to it.narrativeType.name.lowercase(), "timestamp" to it.timestamp))
        }
    }
}
