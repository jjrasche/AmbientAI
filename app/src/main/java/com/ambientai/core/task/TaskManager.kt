package com.ambientai.core.task

import android.content.Context
import com.ambientai.data.entities.TaskStatus
import com.ambientai.data.repositories.TaskRepository

/**
 * Standard result wrapper for all actions.
 */
data class ActionResult(
    val success: Boolean,
    val data: Map<String, Any?> = emptyMap(),
    val error: String? = null
)

/**
 * Actions for task management in workflows.
 * Wraps TaskRepository for workflow execution.
 */
class TaskManager(context: Context) {

    private val repo = TaskRepository(context)

    /**
     * Execute a task action.
     * Returns ActionResult with standardized structure.
     */
    fun execute(actionName: String, input: Map<String, Any?>): ActionResult {
        return when (actionName) {
            "task.start" -> start(input)
            "task.pause" -> pause()
            "task.resume" -> resume(input)
            "task.complete" -> complete()
            "task.status" -> status()
            "task.getActive" -> getActive()
            "task.getNonCompleted" -> getNonCompleted()
            "task.matchTask" -> matchTask(input)
            else -> ActionResult(
                success = false,
                error = "Unknown action: $actionName"
            )
        }
    }

    /**
     * Start a new task.
     * Input: { "name": "task name" }
     * Output: ActionResult with task data or error
     */
    private fun start(input: Map<String, Any?>): ActionResult {
        val name = input["name"] as? String
            ?: return ActionResult(success = false, error = "Missing 'name' parameter")

        return repo.startTask(name).fold(
            onSuccess = { task ->
                ActionResult(
                    success = true,
                    data = mapOf("task" to taskToMap(task))
                )
            },
            onFailure = { error ->
                ActionResult(
                    success = false,
                    error = error.message
                )
            }
        )
    }

    /**
     * Pause currently active task.
     * Input: {}
     * Output: ActionResult with task data or error
     */
    private fun pause(): ActionResult {
        return repo.pauseTask().fold(
            onSuccess = { task ->
                ActionResult(
                    success = true,
                    data = mapOf("task" to taskToMap(task))
                )
            },
            onFailure = { error ->
                ActionResult(
                    success = false,
                    error = error.message
                )
            }
        )
    }

    /**
     * Resume a paused task.
     * Input: { "taskId": 123 } (optional - defaults to most recent paused)
     * Output: ActionResult with task data or error
     */
    private fun resume(input: Map<String, Any?>): ActionResult {
        val taskId = if (input.containsKey("taskId")) {
            (input["taskId"] as? Number)?.toLong()
                ?: return ActionResult(success = false, error = "Invalid taskId")
        } else {
            repo.getMostRecentPaused()?.id
                ?: return ActionResult(success = false, error = "No paused task found")
        }

        return repo.resumeTask(taskId).fold(
            onSuccess = { task ->
                ActionResult(
                    success = true,
                    data = mapOf("task" to taskToMap(task))
                )
            },
            onFailure = { error ->
                ActionResult(
                    success = false,
                    error = error.message
                )
            }
        )
    }

    /**
     * Complete currently active task.
     * Input: {}
     * Output: ActionResult with task data or error
     */
    private fun complete(): ActionResult {
        return repo.completeTask().fold(
            onSuccess = { task ->
                ActionResult(
                    success = true,
                    data = mapOf("task" to taskToMap(task))
                )
            },
            onFailure = { error ->
                ActionResult(
                    success = false,
                    error = error.message
                )
            }
        )
    }

    /**
     * Get status of currently active task.
     * Input: {}
     * Output: ActionResult with status data
     */
    private fun status(): ActionResult {
        val task = repo.getActive()

        return if (task == null) {
            ActionResult(
                success = true,
                data = mapOf("hasActive" to false)
            )
        } else {
            val elapsedMs = task.totalElapsedMs()
            ActionResult(
                success = true,
                data = mapOf(
                    "hasActive" to true,
                    "name" to task.name,
                    "elapsed" to repo.formatDuration(elapsedMs),
                    "elapsedMs" to elapsedMs,
                    "sessionCount" to repo.getSessionCount(task.id)
                )
            )
        }
    }

    /**
     * Get currently active task.
     * Input: {}
     * Output: ActionResult with task data or empty
     */
    private fun getActive(): ActionResult {
        val task = repo.getActive()

        return if (task == null) {
            ActionResult(
                success = true,
                data = mapOf("hasActive" to false)
            )
        } else {
            ActionResult(
                success = true,
                data = mapOf(
                    "hasActive" to true,
                    "task" to taskToMap(task)
                )
            )
        }
    }

    /**
     * Get all non-completed tasks.
     * Input: {}
     * Output: ActionResult with list of tasks
     */
    private fun getNonCompleted(): ActionResult {
        val active = repo.getByStatus(TaskStatus.ACTIVE)
        val paused = repo.getByStatus(TaskStatus.PAUSED)
        val allTasks = active + paused

        val tasksList = allTasks.map { taskToMap(it) }

        return ActionResult(
            success = true,
            data = mapOf("tasks" to tasksList)
        )
    }

    /**
     * Match a task name from transcript (stub for future LLM integration).
     * Input: { "transcript": "..." }
     * Output: ActionResult with matched task or error
     */
    private fun matchTask(input: Map<String, Any?>): ActionResult {
        // TODO: Implement LLM-based task name extraction
        return ActionResult(
            success = false,
            error = "Not yet implemented"
        )
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
            "elapsed" to repo.formatDuration(task.totalElapsedMs()),
            "sessionCount" to repo.getSessionCount(task.id)
        )
    }
}
