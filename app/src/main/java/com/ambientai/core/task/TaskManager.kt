package com.ambientai.core.task

import com.ambientai.data.entities.TaskStatus
import com.ambientai.data.repositories.ITaskRepository
import com.ambientai.util.toHumanDuration
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TaskManager @Inject constructor(
    private val repo: ITaskRepository
) {

    fun execute(actionName: String, input: JSONObject) = when (actionName) { "task.start" -> start(input); "task.pause" -> pause(); "task.complete" -> complete(); "task.getActive" -> getActive(); "task.getNonCompleted" -> getNonCompleted(); else -> throw Exception("Unknown action: $actionName") }
    private fun start(input: JSONObject) = input.optString("name", null)?.takeIf { it.isNotBlank() }?.let { name -> runCatching { pause() }; JSONObject(taskToMap(repo.startTask(name))) } ?: throw Exception("${if (input.optString("name", null) == null) "Missing required field: name" else "Task name cannot be empty"}")
    private fun pause() = JSONObject(taskToMap(repo.pauseTask()))
    private fun complete() = JSONObject(taskToMap(repo.completeTask()))
    private fun getActive() = JSONObject(taskToMap(repo.getActive()))
    private fun getNonCompleted() = JSONObject(mapOf("tasks" to (repo.getByStatus(TaskStatus.ACTIVE) + repo.getByStatus(TaskStatus.PAUSED)).map(::taskToMap)))
    private fun taskToMap(task: com.ambientai.data.entities.Task) = mapOf("id" to task.id, "name" to task.name, "status" to task.status.name, "createdAt" to task.createdAt, "completedAt" to task.completedAt, "elapsedMs" to task.totalElapsedMs(), "elapsed" to task.totalElapsedMs().toHumanDuration(), "sessionCount" to repo.getSessionCount(task.id))
}
