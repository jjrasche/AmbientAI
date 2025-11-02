package com.ambientai.data.entities

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.relation.ToMany

/**
 * Task entity with session-based time tracking.
 * Each pause/resume cycle creates a TaskSession record.
 */
@Entity
data class Task(
    @Id var id: Long = 0,
    var name: String,
    var status: TaskStatus,  // active, paused, completed
    var createdAt: Long,
    var completedAt: Long? = null
) {
    lateinit var sessions: ToMany<TaskSession>

    /**
     * Calculate total elapsed time across all sessions.
     * For active task, includes current session up to now.
     */
    fun totalElapsedMs(): Long {
        var total = 0L
        sessions.forEach { session ->
            total += session.durationMs()
        }
        return total
    }

    /**
     * Get current active session, if any.
     */
    fun currentSession(): TaskSession? {
        return sessions.firstOrNull { it.endedAt == null }
    }
}

enum class TaskStatus {
    ACTIVE,
    PAUSED,
    COMPLETED
}

/**
 * Represents one continuous work session on a task.
 * Created on task start or resume, ended on pause or complete.
 */
@Entity
data class TaskSession(
    @Id var id: Long = 0,
    var taskId: Long,  // Foreign key to Task
    var startedAt: Long,
    var endedAt: Long? = null  // null = currently active
) {
    /**
     * Duration of this session.
     * If still active, duration from start until now.
     */
    fun durationMs(): Long {
        return (endedAt ?: System.currentTimeMillis()) - startedAt
    }
}