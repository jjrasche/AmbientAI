package com.ambientai.core.task

import com.ambientai.data.entities.TaskStatus
import com.ambientai.data.repositories.TaskRepository
import com.ambientai.util.toHumanDuration
import org.json.JSONObject

class TaskManager {
    private val repo = TaskRepository()

    fun execute(actionName: String, input: JSONObject) = when (actionName) {
        "task.start" -> start(input)
        "task.pause" -> pause()
        "task.complete" -> complete()
        "task.getActive" -> getActive()
        "task.getNonCompleted" -> getNonCompleted()
        else -> throw Exception("Unknown action: $actionName")
    }
    private fun start(input: JSONObject): JSONObject {
        val name = input.optString("name", null) ?: throw Exception("Missing required field: name")
        if (name.isBlank()) throw Exception("Task name cannot be empty")
        try { pause() } catch (e: Exception) {}
        return JSONObject(taskToMap(repo.startTask(name)))
    }
    private fun pause() = JSONObject(taskToMap(repo.pauseTask()))
    private fun complete() = JSONObject(taskToMap(repo.completeTask()))
    private fun getActive() = JSONObject(taskToMap(repo.getActive()))
    private fun getNonCompleted() = JSONObject(mapOf("tasks" to (repo.getByStatus(TaskStatus.ACTIVE) + repo.getByStatus(TaskStatus.PAUSED)).map(::taskToMap)))
    private fun taskToMap(task: com.ambientai.data.entities.Task) = mapOf(
        "id" to task.id, "name" to task.name, "status" to task.status.name, "createdAt" to task.createdAt,
        "completedAt" to task.completedAt, "elapsedMs" to task.totalElapsedMs(), "elapsed" to task.totalElapsedMs().toHumanDuration(),
        "sessionCount" to repo.getSessionCount(task.id)
    )
}
