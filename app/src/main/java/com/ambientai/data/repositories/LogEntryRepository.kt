package com.ambientai.data.repositories

import com.ambientai.AmbientAIApp
import com.ambientai.data.entities.LogEntry
import com.ambientai.data.entities.LogEntry_
import io.objectbox.Box
import io.objectbox.kotlin.boxFor
import io.objectbox.query.OrderFlags
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

class LogEntryRepository {
    private val box: Box<LogEntry> = AmbientAIApp.boxStore.boxFor()

    fun save(entry: LogEntry) = entry.also { box.put(it) }
    fun getById(id: Long) = box.get(id)
    fun delete(id: Long) = box.remove(id)
    fun deleteAll() = box.removeAll()
    fun count() = box.count()
    fun countByType(type: String) = box.query(LogEntry_.type.equal(type)).build().count()
    fun getAll() = box.query().order(LogEntry_.timestamp, OrderFlags.DESCENDING).build().find()
    fun getByType(type: String) = box.query(LogEntry_.type.equal(type)).order(LogEntry_.timestamp, OrderFlags.DESCENDING).build().find()
    fun getByTimeRange(startTime: Long, endTime: Long) = box.query().between(LogEntry_.timestamp, startTime, endTime).order(LogEntry_.timestamp, OrderFlags.DESCENDING).build().find()
    fun getAllLogs(): Flow<List<LogEntry>> = callbackFlow {
        val subscription = box.query().order(LogEntry_.timestamp, OrderFlags.DESCENDING).build().subscribe().observer { trySend(it) }
        awaitClose { subscription.cancel() }
    }
}
