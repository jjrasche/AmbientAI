package com.ambientai.core.task

import android.content.Context
import com.ambientai.data.repositories.TaskRepository
import org.json.JSONObject

/**
 * Actions for task management in workflows.
 * Wraps TaskRepository for workflow execution.
 */
class TaskManager(context: Context) {

    private val repo = TaskRepository(context)

    /**
     * Execute a task action.
     * Returns JSONObject with result.
     */
    fun execute(actionName: String, input: JSONObject): JSONObject {
        return when (actionName) {
            "task.start" -> start(input)
            "task.pause" -> pause()
            "task.resume" -> resume(input)
            "task.complete" -> complete()
            "task.status" -> status()
            "task.getActive" -> getActive()
            else -> throw UnknownActionException(actionName)
        }
    }

    /**
     * Start a new task.
     * Input: { "name": "task name" }
     * Output: { "success": true, "task": {...} } | { "success": false, "error": "..." }
     */
    private fun start(input: JSONObject): JSONObject {
        val name = input.getString("name")

        return repo.startTask(name).fold(
            onSuccess = { task ->
                JSONObject().apply {
                    put("success", true)
                    put("task", taskToJson(task))
                }
            },
            onFailure = { error ->
                JSONObject().apply {
                    put("success", false)
                    put("error", error.message)
                }
            }
        )
    }

    /**
     * Pause currently active task.
     * Input: {}
     * Output: { "success": true, "task": {...} } | { "success": false, "error": "..." }
     */
    private fun pause(): JSONObject {
        return repo.pauseTask().fold(
            onSuccess = { task ->
                JSONObject().apply {
                    put("success", true)
                    put("task", taskToJson(task))
                }
            },
            onFailure = { error ->
                JSONObject().apply {
                    put("success", false)
                    put("error", error.message)
                }
            }
        )
    }

    /**
     * Resume a paused task.
     * Input: { "taskId": 123 } (optional - defaults to most recent paused)
     * Output: { "success": true, "task": {...} } | { "success": false, "error": "..." }
     */
    private fun resume(input: JSONObject): JSONObject {
        val taskId = if (input.has("taskId")) {
            input.getLong("taskId")
        } else {
            repo.getMostRecentPaused()?.id
                ?: return JSONObject().apply {
                    put("success", false)
                    put("error", "No paused task found")
                }
        }

        return repo.resumeTask(taskId).fold(
            onSuccess = { task ->
                JSONObject().apply {
                    put("success", true)
                    put("task", taskToJson(task))
                }
            },
            onFailure = { error ->
                JSONObject().apply {
                    put("success", false)
                    put("error", error.message)
                }
            }
        )
    }

    /**
     * Complete currently active task.
     * Input: {}
     * Output: { "success": true, "task": {...} } | { "success": false, "error": "..." }
     */
    private fun complete(): JSONObject {
        return repo.completeTask().fold(
            onSuccess = { task ->
                JSONObject().apply {
                    put("success", true)
                    put("task", taskToJson(task))
                }
            },
            onFailure = { error ->
                JSONObject().apply {
                    put("success", false)
                    put("error", error.message)
                }
            }
        )
    }

    /**
     * Get status of currently active task.
     * Input: {}
     * Output: { "hasActive": true, "name": "...", "elapsed": "2 hours 15 minutes", "elapsedMs": 8100000, "sessionCount": 3 }
     *      or { "hasActive": false }
     */
    private fun status(): JSONObject {
        val task = repo.getActive()

        return if (task == null) {
            JSONObject().apply {
                put("hasActive", false)
            }
        } else {
            val elapsedMs = task.totalElapsedMs()
            JSONObject().apply {
                put("hasActive", true)
                put("name", task.name)
                put("elapsed", repo.formatDuration(elapsedMs))
                put("elapsedMs", elapsedMs)
                put("sessionCount", repo.getSessionCount(task.id))
            }
        }
    }

    /**
     * Get currently active task.
     * Input: {}
     * Output: { "hasActive": true, "task": {...} } | { "hasActive": false }
     */
    private fun getActive(): JSONObject {
        val task = repo.getActive()

        return if (task == null) {
            JSONObject().apply {
                put("hasActive", false)
            }
        } else {
            JSONObject().apply {
                put("hasActive", true)
                put("task", taskToJson(task))
            }
        }
    }

    /**
     * Convert Task to JSON representation.
     */
    private fun taskToJson(task: com.ambientai.data.entities.Task): JSONObject {
        return JSONObject().apply {
            put("id", task.id)
            put("name", task.name)
            put("status", task.status.name)
            put("createdAt", task.createdAt)
            put("completedAt", task.completedAt)
            put("elapsedMs", task.totalElapsedMs())
            put("elapsed", repo.formatDuration(task.totalElapsedMs()))
            put("sessionCount", repo.getSessionCount(task.id))
        }
    }
}

class UnknownActionException(actionName: String) : Exception("Unknown action: $actionName")