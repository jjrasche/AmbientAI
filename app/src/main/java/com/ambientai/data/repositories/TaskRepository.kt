package com.ambientai.data.repositories

import android.content.Context
import android.util.Log
import com.ambientai.AmbientAIApp
import com.ambientai.data.entities.Task
import com.ambientai.data.entities.TaskSession
import com.ambientai.data.entities.TaskStatus
import com.ambientai.data.entities.Task_
import com.ambientai.data.entities.TaskSession_
import io.objectbox.Box
import io.objectbox.kotlin.boxFor
import io.objectbox.query.OrderFlags
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class TaskRepository() {
    private val taskBox: Box<Task> = AmbientAIApp.boxStore.boxFor()
    private val sessionBox: Box<TaskSession> = AmbientAIApp.boxStore.boxFor()

    companion object {
        private const val TAG = "TaskRepository"
    }

    fun getAllTasks(): Flow<List<Task>> = callbackFlow {
        val query = taskBox.query().order(Task_.createdAt, OrderFlags.DESCENDING).build()
        val subscription = query.subscribe().observer { data -> trySend(data) }
        awaitClose { subscription.cancel(); Log.d(TAG, "Tasks flow collection cancelled") }
    }

    fun startTask(name: String): Task {
        val now = System.currentTimeMillis()
        val task = Task(name = name, status = TaskStatus.ACTIVE, createdAt = now)
        taskBox.put(task)
        val session = TaskSession(taskId = task.id, startedAt = now)
        sessionBox.put(session)
        return task
    }

    fun pauseTask(): Task {
        val task = getActive()
        val currentSession = task.sessions?.get(0) ?: throw IllegalStateException("No active session for task ${task.id}")
        currentSession.endedAt = System.currentTimeMillis()
        sessionBox.put(currentSession)
        task.status = TaskStatus.PAUSED
        taskBox.put(task)
        return task
    }

    private fun getCurrentSession(taskId: Long): TaskSession? {
        return sessionBox.query(TaskSession_.taskId.equal(taskId)).isNull(TaskSession_.endedAt).build().findFirst()
    }

    fun completeTask(): Task {
        val task = getActive()
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

    fun getActive(): Task {
        return taskBox.query(Task_.status.equal(TaskStatus.ACTIVE.ordinal.toLong())).build().findFirst() ?: throw Exception("No active task")
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

    fun deleteAll() {
        sessionBox.removeAll()
        taskBox.removeAll()
    }

    fun count(): Long {
        return taskBox.count()
    }
}