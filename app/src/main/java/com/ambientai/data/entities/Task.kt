package com.ambientai.data.entities

import io.objectbox.annotation.Convert
import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.converter.PropertyConverter
import io.objectbox.relation.ToMany

@Entity
data class Task(@Id var id: Long = 0, var name: String = "", @Convert(converter = TaskStatusConverter::class, dbType = Int::class) var status: TaskStatus = TaskStatus.ACTIVE, var createdAt: Long = 0, var completedAt: Long? = null) {
    var sessions: ToMany<TaskSession>? = null

    fun totalElapsedMs() = sessions?.sumOf { it.durationMs() } ?: 0L
    fun currentSession() = sessions?.firstOrNull { it.endedAt == null }
}
enum class TaskStatus { ACTIVE, PAUSED, COMPLETED }
class TaskStatusConverter : PropertyConverter<TaskStatus, Int> {
    override fun convertToDatabaseValue(entityProperty: TaskStatus?) = entityProperty?.ordinal ?: 0
    override fun convertToEntityProperty(databaseValue: Int?) = TaskStatus.entries.getOrNull(databaseValue ?: 0) ?: TaskStatus.ACTIVE
}
@Entity
data class TaskSession(@Id var id: Long = 0, var taskId: Long = 0, var startedAt: Long = 0, var endedAt: Long? = null) {
    fun durationMs() = (endedAt ?: System.currentTimeMillis()) - startedAt
}
