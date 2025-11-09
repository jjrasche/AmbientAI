package com.ambientai.data.repositories

import com.ambientai.data.entities.LogEntry
import com.ambientai.data.entities.LogEntry_
import io.objectbox.Box
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import io.objectbox.query.OrderFlags
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LogEntryRepository @Inject constructor(
    private val boxStore: BoxStore
) : ILogEntryRepository {
    private val box: Box<LogEntry> = boxStore.boxFor()

    override fun save(entry: LogEntry) = entry.also { box.put(it) }
    override fun getById(id: Long) = box.get(id)
    override fun delete(id: Long) = box.remove(id)
    override fun deleteAll() = box.removeAll()
    override fun count() = box.count()
    override fun countByType(type: String) = box.query(LogEntry_.type.equal(type)).build().count()
    override fun getAll() = box.query().order(LogEntry_.timestamp, OrderFlags.DESCENDING).build().find()
    override fun getByType(type: String) = box.query(LogEntry_.type.equal(type)).order(LogEntry_.timestamp, OrderFlags.DESCENDING).build().find()
    override fun getByTimeRange(startTime: Long, endTime: Long) = box.query().between(LogEntry_.timestamp, startTime, endTime).order(LogEntry_.timestamp, OrderFlags.DESCENDING).build().find()
    override fun getAllLogs(): Flow<List<LogEntry>> = callbackFlow {
        val subscription = box.query().order(LogEntry_.timestamp, OrderFlags.DESCENDING).build().subscribe().observer { trySend(it) }
        awaitClose { subscription.cancel() }
    }
}
