package com.ambientai.data.repositories

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

class TaskRepository {
    private val taskBox: Box<Task> = AmbientAIApp.boxStore.boxFor()
    private val sessionBox: Box<TaskSession> = AmbientAIApp.boxStore.boxFor()

    fun getById(id: Long) = taskBox.get(id)
    fun getAll() = taskBox.query().order(Task_.createdAt, OrderFlags.DESCENDING).build().find()
    fun getByStatus(status: TaskStatus) = taskBox.query(Task_.status.equal(status.ordinal.toLong())).order(Task_.createdAt, OrderFlags.DESCENDING).build().find()
    fun getActive() = taskBox.query(Task_.status.equal(TaskStatus.ACTIVE.ordinal.toLong())).build().findFirst() ?: throw Exception("No active task")
    fun getMostRecentPaused() = taskBox.query(Task_.status.equal(TaskStatus.PAUSED.ordinal.toLong())).order(Task_.createdAt, OrderFlags.DESCENDING).build().findFirst()
    fun count() = taskBox.count()

    fun getSessions(taskId: Long) = sessionBox.query(TaskSession_.taskId.equal(taskId)).order(TaskSession_.startedAt).build().find()
    fun getSessionCount(taskId: Long) = sessionBox.query(TaskSession_.taskId.equal(taskId)).build().count()

    fun startTask(name: String) = System.currentTimeMillis().let { now ->
        Task(name = name, status = TaskStatus.ACTIVE, createdAt = now).also {
            taskBox.put(it)
            sessionBox.put(TaskSession(taskId = it.id, startedAt = now))
        }
    }

    fun pauseTask() = getActive().also {
        (it.sessions?.get(0) ?: throw IllegalStateException("No active session for task ${it.id}"))
            .apply { endedAt = System.currentTimeMillis() }
            .let(sessionBox::put)
        it.status = TaskStatus.PAUSED
        taskBox.put(it)
    }

    fun completeTask() = getActive().also {
        it.currentSession()?.apply { endedAt = System.currentTimeMillis() }?.let(sessionBox::put)
        it.status = TaskStatus.COMPLETED
        it.completedAt = System.currentTimeMillis()
        taskBox.put(it)
    }

    fun delete(taskId: Long) = getSessions(taskId).forEach(sessionBox::remove).let { taskBox.remove(taskId) }
    fun deleteAll() = sessionBox.removeAll().also { taskBox.removeAll() }

    fun getAllTasks(): Flow<List<Task>> = callbackFlow {
        val subscription = taskBox.query().order(Task_.createdAt, OrderFlags.DESCENDING).build().subscribe().observer { trySend(it) }
        awaitClose { subscription.cancel() }
    }
}
