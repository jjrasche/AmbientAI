package com.ambientai.core.task

import android.content.Context
import com.ambientai.data.entities.TaskStatus
import com.ambientai.data.repositories.TaskRepository
import com.ambientai.util.toHumanDuration
import org.json.JSONObject

class TaskManager(context: Context) {
    private val repo = TaskRepository(context)
    fun execute(actionName: String, input: JSONObject): JSONObject {
        return when (actionName) {
            "task.start" -> start(input)
            "task.pause" -> pause()
            "task.complete" -> complete()
            "task.getActive" -> getActive()
            "task.getNonCompleted" -> getNonCompleted(input)
            else -> throw Exception("Unknown action: $actionName")
        }
    }
    private fun start(input: JSONObject): JSONObject {
        val name = input.optString("name", null) ?: throw Exception ("Missing required field: name")
        if (name.isBlank()) throw Exception("Task name cannot be empty")
        // Auto-pause the active task
        try { pause() } catch (e: Exception) {}
        // Start new task
        val task = repo.startTask(name)
        return JSONObject(taskToMap(task))
    }
    private fun pause(): JSONObject {
        return JSONObject(taskToMap(repo.pauseTask()))
    }
    private fun complete(): JSONObject {
        return JSONObject(taskToMap(repo.completeTask()))
    }
    private fun getActive(): JSONObject {
        return JSONObject(taskToMap(repo.getActive()))
    }
    private fun getNonCompleted(input: JSONObject): JSONObject {
        val allTasks = repo.getByStatus(TaskStatus.ACTIVE) + repo.getByStatus(TaskStatus.PAUSED)
        val tasksList = allTasks.map { taskToMap(it) }
        return JSONObject(mapOf("tasks" to tasksList))
    }
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