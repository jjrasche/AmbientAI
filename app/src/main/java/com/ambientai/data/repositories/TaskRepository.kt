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

/**
 * Repository for Task and TaskSession entities.
 * Handles session-based time tracking.
 */
class TaskRepository(context: Context) {

    private val taskBox: Box<Task> = AmbientAIApp.boxStore.boxFor()
    private val sessionBox: Box<TaskSession> = AmbientAIApp.boxStore.boxFor()

    /**
     * Start a new task.
     * Creates Task + first TaskSession.
     * Fails if another task is already active.
     */
    fun startTask(name: String): Result<Task> {
        val existing = getActive()
        if (existing != null) {
            return Result.failure(Exception("Task '${existing.name}' is already active"))
        }

        val now = System.currentTimeMillis()

        val task = Task(
            name = name,
            status = TaskStatus.ACTIVE,
            createdAt = now
        )
        taskBox.put(task)
        task.sessions?.reset()  // Initialize relation

        // Create first session
        val session = TaskSession(
            taskId = task.id,
            startedAt = now
        )
        sessionBox.put(session)
        task.sessions?.add(session)

        return Result.success(task)
    }

    /**
     * Pause the currently active task.
     * Ends current session, sets status to PAUSED.
     */
    fun pauseTask(): Result<Task> {
        val task = getActive() ?: return Result.failure(Exception("No active task"))

        val session = task.currentSession()
            ?: return Result.failure(Exception("No active session"))

        session.endedAt = System.currentTimeMillis()
        sessionBox.put(session)

        task.status = TaskStatus.PAUSED
        taskBox.put(task)

        return Result.success(task)
    }

    /**
     * Resume a paused task.
     * Creates new TaskSession, sets status to ACTIVE.
     */
    fun resumeTask(taskId: Long): Result<Task> {
        val task = taskBox.get(taskId)
            ?: return Result.failure(Exception("Task not found"))

        if (task.status != TaskStatus.PAUSED) {
            return Result.failure(Exception("Task is not paused"))
        }

        val existing = getActive()
        if (existing != null) {
            return Result.failure(Exception("Task '${existing.name}' is already active"))
        }

        // Create new session
        val session = TaskSession(
            taskId = task.id,
            startedAt = System.currentTimeMillis()
        )
        sessionBox.put(session)
        task.sessions?.add(session)

        task.status = TaskStatus.ACTIVE
        taskBox.put(task)

        return Result.success(task)
    }

    /**
     * Complete the currently active task.
     * Ends current session, sets status to COMPLETED.
     */
    fun completeTask(): Result<Task> {
        val task = getActive() ?: return Result.failure(Exception("No active task"))

        val session = task.currentSession()
        if (session != null) {
            session.endedAt = System.currentTimeMillis()
            sessionBox.put(session)
        }

        task.status = TaskStatus.COMPLETED
        task.completedAt = System.currentTimeMillis()
        taskBox.put(task)

        return Result.success(task)
    }

    /**
     * Get currently active task.
     */
    fun getActive(): Task? {
        return taskBox.query(Task_.status.equal(TaskStatus.ACTIVE.ordinal.toLong()))
            .build()
            .findFirst()
    }

    /**
     * Get most recently paused task.
     */
    fun getMostRecentPaused(): Task? {
        return taskBox.query(Task_.status.equal(TaskStatus.PAUSED.ordinal.toLong()))
            .order(Task_.createdAt, OrderFlags.DESCENDING)
            .build()
            .findFirst()
    }

    /**
     * Get task by ID.
     */
    fun getById(id: Long): Task? {
        return taskBox.get(id)
    }

    /**
     * Get all tasks.
     */
    fun getAll(): List<Task> {
        return taskBox.query()
            .order(Task_.createdAt, OrderFlags.DESCENDING)
            .build()
            .find()
    }

    /**
     * Get tasks by status.
     */
    fun getByStatus(status: TaskStatus): List<Task> {
        return taskBox.query(Task_.status.equal(status.ordinal.toLong()))
            .order(Task_.createdAt, OrderFlags.DESCENDING)
            .build()
            .find()
    }

    /**
     * Format elapsed time as human-readable string.
     * Examples: "2 hours 15 minutes", "45 minutes", "3 seconds"
     */
    fun formatDuration(ms: Long): String {
        val seconds = ms / 1000
        val minutes = seconds / 60
        val hours = minutes / 60

        return when {
            hours > 0 -> {
                val remainingMinutes = minutes % 60
                if (remainingMinutes > 0) {
                    "$hours hours $remainingMinutes minutes"
                } else {
                    "$hours hours"
                }
            }
            minutes > 0 -> "$minutes minutes"
            else -> "$seconds seconds"
        }
    }

    /**
     * Get all sessions for a task.
     */
    fun getSessions(taskId: Long): List<TaskSession> {
        return sessionBox.query(TaskSession_.taskId.equal(taskId))
            .order(TaskSession_.startedAt)
            .build()
            .find()
    }

    /**
     * Get session count for a task.
     */
    fun getSessionCount(taskId: Long): Long {
        return sessionBox.query(TaskSession_.taskId.equal(taskId))
            .build()
            .count()
    }

    /**
     * Delete a task and all its sessions.
     */
    fun delete(taskId: Long): Boolean {
        // Delete sessions first
        val sessions = getSessions(taskId)
        sessions.forEach { sessionBox.remove(it) }

        // Delete task
        return taskBox.remove(taskId)
    }

    /**
     * Count all tasks.
     */
    fun count(): Long {
        return taskBox.count()
    }
}