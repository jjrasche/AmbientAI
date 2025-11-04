package com.ambientai.data.repositories

import android.content.Context
import android.util.Log
import com.ambientai.AmbientAIApp
import com.ambientai.data.entities.LogEntry
import com.ambientai.data.entities.LogEntry_
import com.ambientai.data.entities.Transcript
import io.objectbox.Box
import io.objectbox.kotlin.boxFor
import io.objectbox.query.OrderFlags
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class LogRepository() {
    private val box: Box<LogEntry> = AmbientAIApp.boxStore.boxFor()
    companion object {
        private const val TAG = "LogRepository"
    }
    fun save(entry: LogEntry): LogEntry {
        box.put(entry)
        Log.d(TAG, "Saved log entry ${entry.id} of type ${entry.type}")
        return entry
    }
    fun getById(id: Long): LogEntry? {
        return box.get(id)
    }
    fun getAll(): List<LogEntry> {
        return box.query().order(LogEntry_.timestamp, OrderFlags.DESCENDING).build().find()
    }
    fun getAllLogs(): Flow<List<LogEntry>> = callbackFlow {
        val query = box.query().order(LogEntry_.timestamp, OrderFlags.DESCENDING).build()
        val subscription = query.subscribe().observer { data -> trySend(data) }
        awaitClose { subscription.cancel() }
    }
    fun getByType(type: String): List<LogEntry> {
        return box.query(LogEntry_.type.equal(type)).order(LogEntry_.timestamp, OrderFlags.DESCENDING).build().find()
    }
    fun getByTimeRange(startTime: Long, endTime: Long): List<LogEntry> {
        return box.query().between(LogEntry_.timestamp, startTime, endTime).order(LogEntry_.timestamp, OrderFlags.DESCENDING).build().find()
    }
    fun delete(id: Long): Boolean {
        return box.remove(id)
    }
    fun deleteAll() {
        box.removeAll()
        Log.d(TAG, "Deleted all log entries")
    }
    fun count(): Long {
        return box.count()
    }
    fun countByType(type: String): Long {
        return box.query(LogEntry_.type.equal(type)).build().count()
    }
}