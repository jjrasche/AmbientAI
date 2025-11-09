package com.ambientai.data.repositories

import com.ambientai.data.entities.LogEntry
import kotlinx.coroutines.flow.Flow

interface ILogEntryRepository {
    // CRUD operations
    fun save(logEntry: LogEntry): LogEntry
    fun getById(id: Long): LogEntry?
    fun delete(id: Long)
    fun deleteAll()

    // Queries
    fun getAll(): List<LogEntry>
    fun count(): Long
    fun countByType(type: String): Long
    fun getByType(type: String): List<LogEntry>
    fun getByTimeRange(startTime: Long, endTime: Long): List<LogEntry>

    // Reactive streams
    fun getAllLogs(): Flow<List<LogEntry>>
}
