package com.ambientai.data.repositories

import com.ambientai.data.entities.LogEntry

interface ILogEntryRepository {
    // CRUD operations
    fun save(logEntry: LogEntry): LogEntry
    fun getById(id: Long): LogEntry?
    fun deleteAll()

    // Queries
    fun getAll(): List<LogEntry>
    fun count(): Long
    fun getByType(type: String): List<LogEntry>
}
