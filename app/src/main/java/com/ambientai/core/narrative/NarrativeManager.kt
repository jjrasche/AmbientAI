package com.ambientai.core.narrative

import com.ambientai.data.entities.Narrative
import com.ambientai.data.entities.NarrativeType
import com.ambientai.data.repositories.IActionExecutionRepository
import com.ambientai.data.repositories.INarrativeRepository
import com.ambientai.data.repositories.ITaskRepository
import com.ambientai.data.repositories.ITranscriptRepository
import com.ambientai.util.toHumanDuration
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

data class CurrentState(val timestamp: Long, val activeTask: String?, val pausedTasks: String, val recentConversation: String, val recentQueries: String) {
    fun toJson() = JSONObject().apply { put("timestamp", timestamp); put("currentTime", SimpleDateFormat("h:mm a, EEEE MMM d", Locale.US).format(Date(timestamp))); put("activeTask", activeTask ?: "none"); put("pausedTasks", pausedTasks); put("recentConversation", recentConversation); put("recentQueries", recentQueries) }.toString(2)
}

@Singleton
class NarrativeManager @Inject constructor(
    private val narrativeRepo: INarrativeRepository,
    private val taskRepo: ITaskRepository,
    private val transcriptRepo: ITranscriptRepository,
    private val actionRepo: IActionExecutionRepository
) {
    fun execute(actionName: String, input: JSONObject) = when (actionName) { "state.gather" -> gatherState(); "narrative.save" -> saveNarrative(input); else -> throw Exception("Unknown action: $actionName") }
    private fun gatherState() = System.currentTimeMillis().let { now -> JSONObject(mapOf("state" to CurrentState(timestamp = now, activeTask = runCatching { taskRepo.getActive().let { "${it.name} (${it.totalElapsedMs().toHumanDuration()}, ${taskRepo.getSessionCount(it.id)} sessions)" } }.getOrNull(), pausedTasks = taskRepo.getByStatus(com.ambientai.data.entities.TaskStatus.PAUSED).take(5).joinToString(", ") { "${it.name} (${it.totalElapsedMs().toHumanDuration()})" }.ifEmpty { "none" }, recentConversation = transcriptRepo.getRecentContext(10).ifEmpty { "No recent conversation" }, recentQueries = actionRepo.getByActionName("llm.prompt").take(5).reversed().mapNotNull { JSONObject(it.inputJson).optString("userPrompt", "").takeIf { p -> p.isNotEmpty() }?.let { p -> if (p.length >= 200) p.take(200) + "..." else p } }.joinToString("\n- ", prefix = "- ").ifEmpty { "No recent queries" }).toJson())) }
    private fun saveNarrative(input: JSONObject) = input.optString("text", null)?.takeIf { it.isNotBlank() }?.let { text -> input.optString("stateSnapshot", null)?.let { stateSnapshot -> runCatching { NarrativeType.valueOf(input.optString("narrativeType", "working").uppercase()) }.getOrDefault(NarrativeType.WORKING).let { narrativeType -> narrativeRepo.save(Narrative(text = text, narrativeType = narrativeType, stateSnapshot = stateSnapshot, timestamp = System.currentTimeMillis())).let { JSONObject(mapOf("id" to it.id, "narrativeType" to it.narrativeType.name.lowercase(), "timestamp" to it.timestamp)) } } } ?: throw Exception("Missing required field: stateSnapshot") } ?: throw Exception("${if (input.optString("text", null) == null) "Missing required field: text" else "Narrative text cannot be empty"}")
}
