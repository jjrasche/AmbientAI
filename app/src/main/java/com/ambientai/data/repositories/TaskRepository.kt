package com.ambientai.data.repositories

import android.content.Context
import com.ambientai.AmbientAIApp
import com.ambientai.data.entities.Task
import com.ambientai.data.entities.TaskSession
import com.ambientai.data.entities.TaskStatus
import com.ambientai.data.entities.Task_
import com.ambientai.data.entities.TaskSession_
import io.objectbox.Box
import io.objectbox.kotlin.boxFor
import io.objectbox.query.OrderFlags

class TaskRepository(context: Context) {
    private val taskBox: Box<Task> = AmbientAIApp.boxStore.boxFor()
    private val sessionBox: Box<TaskSession> = AmbientAIApp.boxStore.boxFor()
    fun startTask(name: String): Result<Task> {
        val now = System.currentTimeMillis()
        val task = Task(name = name, status = TaskStatus.ACTIVE, createdAt = now)
        taskBox.put(task)
        val session = TaskSession(taskId = task.id, startedAt = now)
        sessionBox.put(session)
        return Result.success(task)
    }
    fun pauseTask(task: Task): Task {
        val session = getCurrentSession(task.id) ?: throw IllegalStateException("No active session for task ${task.id}")
        session.endedAt = System.currentTimeMillis()
        sessionBox.put(session)
        task.status = TaskStatus.PAUSED
        taskBox.put(task)
        return task
    }
    private fun getCurrentSession(taskId: Long): TaskSession? {
        return sessionBox.query(TaskSession_.taskId.equal(taskId)).isNull(TaskSession_.endedAt).build().findFirst()
    }
    fun resumeTask(taskId: Long): Task {
        val task = taskBox.get(taskId) ?: throw Exception("Task not found")
        if (task.status != TaskStatus.PAUSED) throw Exception("Task is not paused")
        val existing = getActive()
        if (existing != null) throw Exception("Task '${existing.name}' is already active")
        // Create new session
        val session = TaskSession( taskId = task.id,  startedAt = System.currentTimeMillis() )
        sessionBox.put(session)
        task.sessions?.add(session)
        task.status = TaskStatus.ACTIVE
        taskBox.put(task)
        return task
    }
    fun completeTask(): Task {
        val task = getActive() ?: throw Exception("No active task")
        val session = task.currentSession()
        if (session != null) {
            session.endedAt = System.currentTimeMillis()
            sessionBox.put(session)
        }
        task.status = TaskStatus.COMPLETED
        task.completedAt = System.currentTimeMillis()
        taskBox.put(task)
        return task
    }
    fun getActive(): Task? {
        return taskBox.query(Task_.status.equal(TaskStatus.ACTIVE.ordinal.toLong())).build().findFirst()
    }
    fun getMostRecentPaused(): Task? {
        return taskBox.query(Task_.status.equal(TaskStatus.PAUSED.ordinal.toLong())).order(Task_.createdAt, OrderFlags.DESCENDING).build().findFirst()
    }
    fun getById(id: Long): Task? {
        return taskBox.get(id)
    }
    fun getAll(): List<Task> {
        return taskBox.query().order(Task_.createdAt, OrderFlags.DESCENDING).build().find()
    }
    fun getByStatus(status: TaskStatus): List<Task> {
        return taskBox.query(Task_.status.equal(status.ordinal.toLong())).order(Task_.createdAt, OrderFlags.DESCENDING).build().find()
    }
    fun getSessions(taskId: Long): List<TaskSession> {
        return sessionBox.query(TaskSession_.taskId.equal(taskId)).order(TaskSession_.startedAt).build().find()
    }
    fun getSessionCount(taskId: Long): Long {
        return sessionBox.query(TaskSession_.taskId.equal(taskId)).build().count()
    }
    fun delete(taskId: Long): Boolean {
        val sessions = getSessions(taskId)
        sessions.forEach { sessionBox.remove(it) }
        return taskBox.remove(taskId)
    }
    fun count(): Long {
        return taskBox.count()
    }
}