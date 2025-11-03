package com.ambientai.data.entities

import io.objectbox.annotation.Convert
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.converter.PropertyConverter
import io.objectbox.relation.ToMany

/**
 * Task entity with session-based time tracking.
 * Each pause/resume cycle creates a TaskSession record.
 */
@Entity
data class Task(
    @Id var id: Long = 0,
    var name: String = "",
    @Convert(converter = TaskStatusConverter::class, dbType = Int::class)
    var status: TaskStatus = TaskStatus.ACTIVE,
    var createdAt: Long = 0,
    var completedAt: Long? = null
) {
    // Remove lateinit - ObjectBox initializes this
    var sessions: ToMany<TaskSession>? = null

    /**
     * Calculate total elapsed time across all sessions.
     * For active task, includes current session up to now.
     */
    fun totalElapsedMs(): Long {
        var total = 0L
        sessions?.forEach { session ->
            total += session.durationMs()
        }
        return total
    }

    /**
     * Get current active session, if any.
     */
    fun currentSession(): TaskSession? {
        return sessions?.firstOrNull { it.endedAt == null }
    }
}

enum class TaskStatus {
    ACTIVE,
    PAUSED,
    COMPLETED
}

/**
 * Converter for TaskStatus enum to Int for ObjectBox storage.
 */
class TaskStatusConverter : PropertyConverter<TaskStatus, Int> {
    override fun convertToDatabaseValue(entityProperty: TaskStatus?): Int {
        return entityProperty?.ordinal ?: 0
    }

    override fun convertToEntityProperty(databaseValue: Int?): TaskStatus {
        return TaskStatus.entries.getOrNull(databaseValue ?: 0) ?: TaskStatus.ACTIVE
    }
}

/**
 * Represents one continuous work session on a task.
 * Created on task start or resume, ended on pause or complete.
 */
@Entity
data class TaskSession(
    @Id var id: Long = 0,
    var taskId: Long = 0,
    var startedAt: Long = 0,
    var endedAt: Long? = null
) {
    /**
     * Duration of this session.
     * If still active, duration from start until now.
     */
    fun durationMs(): Long {
        return (endedAt ?: System.currentTimeMillis()) - startedAt
    }
}