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

/**
 * Current state snapshot for narrative synthesis.
 */
data class CurrentState(
    val timestamp: Long,
    val activeTask: String?,
    val pausedTasks: String,
    val recentConversation: String,
    val recentQueries: String
) {
    fun toJson(): String {
        return JSONObject().apply {
            put("timestamp", timestamp)
            put("currentTime", SimpleDateFormat("h:mm a, EEEE MMM d", Locale.US).format(Date(timestamp)))
            put("activeTask", activeTask ?: "none")
            put("pausedTasks", pausedTasks)
            put("recentConversation", recentConversation)
            put("recentQueries", recentQueries)
        }.toString(2)
    }
}

/**
 * Manages narrative synthesis actions.
 */
class NarrativeManager {
    private val narrativeRepo = NarrativeRepository()
    private val taskRepo = TaskRepository()
    private val transcriptRepo = TranscriptRepository()
    private val actionRepo = ActionExecutionRepository()

    fun execute(actionName: String, input: JSONObject): JSONObject {
        return when (actionName) {
            "state.gather" -> gatherState()
            "narrative.save" -> saveNarrative(input)
            else -> throw Exception("Unknown action: $actionName")
        }
    }

    private fun gatherState(): JSONObject {
        val now = System.currentTimeMillis()

        val activeTask = try {
            val task = taskRepo.getActive()
            val elapsed = task.totalElapsedMs().toHumanDuration()
            val sessionCount = taskRepo.getSessionCount(task.id)
            "${task.name} ($elapsed, $sessionCount sessions)"
        } catch (e: Exception) {
            null
        }

        val pausedTasks = taskRepo.getByStatus(com.ambientai.data.entities.TaskStatus.PAUSED)
            .take(5)
            .joinToString(", ") { task ->
                val elapsed = task.totalElapsedMs().toHumanDuration()
                "${task.name} ($elapsed)"
            }
            .ifEmpty { "none" }

        val recentConversation = transcriptRepo.getRecentContext(10)
            .ifEmpty { "No recent conversation" }

        val recentQueries = actionRepo.getByActionName("llm.prompt")
            .take(5)
            .reversed()
            .mapNotNull { action ->
                try {
                    val input = JSONObject(action.inputJson)
                    val userPrompt = input.optString("userPrompt", "")
                    if (userPrompt.isNotEmpty() && userPrompt.length < 200) {
                        userPrompt
                    } else if (userPrompt.length >= 200) {
                        userPrompt.take(200) + "..."
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    null
                }
            }
            .joinToString("\n- ", prefix = "- ")
            .ifEmpty { "No recent queries" }

        val state = CurrentState(
            timestamp = now,
            activeTask = activeTask,
            pausedTasks = pausedTasks,
            recentConversation = recentConversation,
            recentQueries = recentQueries
        )

        return JSONObject(mapOf("state" to state.toJson()))
    }

    private fun saveNarrative(input: JSONObject): JSONObject {
        val text = input.optString("text", null)
            ?: throw Exception("Missing required field: text")

        val stateSnapshot = input.optString("stateSnapshot", null)
            ?: throw Exception("Missing required field: stateSnapshot")

        if (text.isBlank()) {
            throw Exception("Narrative text cannot be empty")
        }

        val narrativeTypeStr = input.optString("narrativeType", "working").uppercase()
        val narrativeType = try {
            NarrativeType.valueOf(narrativeTypeStr)
        } catch (e: IllegalArgumentException) {
            NarrativeType.WORKING
        }

        val narrative = Narrative(
            text = text,
            narrativeType = narrativeType,
            stateSnapshot = stateSnapshot,
            timestamp = System.currentTimeMillis()
        )

        narrativeRepo.save(narrative)

        return JSONObject(mapOf(
            "id" to narrative.id,
            "narrativeType" to narrative.narrativeType.name.lowercase(),
            "timestamp" to narrative.timestamp
        ))
    }
}