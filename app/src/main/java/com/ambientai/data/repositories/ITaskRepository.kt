package com.ambientai.data.repositories

import com.ambientai.data.entities.Task
import com.ambientai.data.entities.TaskSession
import com.ambientai.data.entities.TaskStatus
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for Task and TaskSession entities.
 *
 * NOTE: This repository manages TWO related entities (Task + TaskSession)
 * because TaskSession cannot exist without a Task (aggregate root pattern).
 */
interface ITaskRepository {
    // Task CRUD
    fun getById(id: Long): Task?
    fun getAll(): List<Task>
    fun getByStatus(status: TaskStatus): List<Task>
    fun count(): Long
    fun delete(taskId: Long)
    fun deleteAll()

    // Task domain operations
    fun getActive(): Task
    fun getMostRecentPaused(): Task?
    fun startTask(name: String): Task
    fun pauseTask(): Task
    fun resumeTask(taskId: Long? = null): Task
    fun completeTask(): Task

    // TaskSession operations
    fun getSessions(taskId: Long): List<TaskSession>
    fun getSessionCount(taskId: Long): Long

    // Reactive streams
    fun getAllTasks(): Flow<List<Task>>
}
