package com.ambientai.core.task

import android.content.Context
import android.util.Log
import com.ambientai.data.entities.TaskStatus
import com.ambientai.data.repositories.TaskRepository
import com.ambientai.util.toHumanDuration
import org.json.JSONObject
import org.json.JSONArray

/**
 * Actions for task management in workflows.
 * Validates inputs and returns JSONObject for workflow compatibility.
 */
class TaskManager(context: Context) {

    private val repo = TaskRepository(context)

    companion object {
        private const val TAG = "TaskManager"
    }
    /**
     * Execute a task action.
     * Input/output is JSONObject for workflow compatibility.
     */
    fun execute(actionName: String, input: JSONObject): JSONObject {
        return when (actionName) {
            "task.start" -> start(input)
            "task.pause" -> pause(input)
            "task.resume" -> resume(input)
            "task.complete" -> complete(input)
            "task.status" -> status(input)
            "task.getActive" -> getActive(input)
            "task.getNonCompleted" -> getNonCompleted(input)
            "task.matchTask" -> matchTask(input)
            else -> errorResult("Unknown action: $actionName")
        }
    }

    private fun successResult(data: Map<String, Any?> = emptyMap()): JSONObject {
        return JSONObject().apply {
            put("success", true)
            data.forEach { (k, v) ->
                when (v) {
                    is List<*> -> put(k, JSONArray(v))
                    is Map<*, *> -> put(k, JSONObject(v as Map<String, Any?>))
                    else -> put(k, v)
                }
            }
        }
    }

    private fun errorResult(message: String): JSONObject {
        return JSONObject().apply {
            put("success", false)
            put("error", message)
        }
    }

    /**
     * Start a new task.
     * Input: { "name": "task name" }
     */
    private fun start(input: JSONObject): JSONObject {
        val name = input.optString("name", null)
            ?: return errorResult("Missing required field: name")

        if (name.isBlank()) {
            return errorResult("Task name cannot be empty")
        }

        // Check for existing active task
        val existingActive = repo.getActive()
        if (existingActive != null) {
            // Auto-pause the active task
            try {
                repo.pauseTask(existingActive)
                Log.d(TAG, "Auto-paused existing task: ${existingActive.name}")
            } catch (e: Exception) {
                return errorResult("Failed to pause existing task: ${e.message}")
            }
        }

        // Start new task
        return try {
            val task = repo.startTask(name)
            successResult(mapOf("task" to taskToMap(task)))
        } catch (e: Exception) {
            errorResult(e.message ?: "Failed to start task")
        }
    }

    /**
     * Pause currently active task.
     * Input: {} (no parameters)
     */
    private fun pause(input: JSONObject): JSONObject {
        return repo.pauseTask().fold(
            onSuccess = { task ->
                successResult(mapOf("task" to taskToMap(task)))
            },
            onFailure = { error ->
                errorResult(error.message ?: "Failed to pause task")
            }
        )
    }

    /**
     * Resume a paused task.
     * Input: { "taskId": 123 } (optional - defaults to most recent paused)
     */
    private fun resume(input: JSONObject): JSONObject {
        val taskId = if (input.has("taskId")) {
            val id = input.optLong("taskId", -1)
            if (id <= 0) {
                return errorResult("Invalid taskId: must be positive number")
            }
            id
        } else {
            repo.getMostRecentPaused()?.id
                ?: return errorResult("No paused task found")
        }

        return repo.resumeTask(taskId).fold(
            onSuccess = { task ->
                successResult(mapOf("task" to taskToMap(task)))
            },
            onFailure = { error ->
                errorResult(error.message ?: "Failed to resume task")
            }
        )
    }

    /**
     * Complete currently active task.
     * Input: {} (no parameters)
     */
    private fun complete(input: JSONObject): JSONObject {
        return repo.completeTask().fold(
            onSuccess = { task ->
                successResult(mapOf("task" to taskToMap(task)))
            },
            onFailure = { error ->
                errorResult(error.message ?: "Failed to complete task")
            }
        )
    }

    /**
     * Get status of currently active task.
     * Input: {} (no parameters)
     */
    private fun status(input: JSONObject): JSONObject {
        val task = repo.getActive()

        return if (task == null) {
            successResult(mapOf("hasActive" to false))
        } else {
            val elapsedMs = task.totalElapsedMs()
            successResult(mapOf(
                "hasActive" to true,
                "name" to task.name,
                "elapsed" to elapsedMs.toHumanDuration(),
                "elapsedMs" to elapsedMs,
                "sessionCount" to repo.getSessionCount(task.id)
            ))
        }
    }

    /**
     * Get currently active task.
     * Input: {} (no parameters)
     */
    private fun getActive(input: JSONObject): JSONObject {
        val task = repo.getActive()

        return if (task == null) {
            successResult(mapOf("hasActive" to false))
        } else {
            successResult(mapOf(
                "hasActive" to true,
                "task" to taskToMap(task)
            ))
        }
    }

    /**
     * Get all non-completed tasks (active + paused).
     * Input: {} (no parameters)
     */
    private fun getNonCompleted(input: JSONObject): JSONObject {
        val active = repo.getByStatus(TaskStatus.ACTIVE)
        val paused = repo.getByStatus(TaskStatus.PAUSED)
        val allTasks = active + paused

        val tasksList = allTasks.map { taskToMap(it) }

        return successResult(mapOf("tasks" to tasksList))
    }

    /**
     * Match a task name from transcript (stub for future LLM integration).
     * Input: { "transcript": "...", "tasks": [...] }
     */
    private fun matchTask(input: JSONObject): JSONObject {
        val transcript = input.optString("transcript", null)
            ?: return errorResult("Missing required field: transcript")

        if (!input.has("tasks")) {
            return errorResult("Missing required field: tasks")
        }

        // TODO: Implement LLM-based task name extraction
        return errorResult("Not yet implemented: task.matchTask")
    }

    /**
     * Convert Task entity to Map representation.
     */
    private fun taskToMap(task: com.ambientai.data.entities.Task): Map<String, Any?> {
        return mapOf(
            "id" to task.id,
            "name" to task.name,
            "status" to task.status.name,
            "createdAt" to task.createdAt,
            "completedAt" to task.completedAt,
            "elapsedMs" to task.totalElapsedMs(),
            "elapsed" to task.totalElapsedMs().toHumanDuration(),
            "sessionCount" to repo.getSessionCount(task.id)
        )
    }
}