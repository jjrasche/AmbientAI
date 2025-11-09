package com.ambientai.data.repositories

import com.ambientai.data.entities.Transcript
import com.ambientai.data.entities.Transcript_
import io.objectbox.Box
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import io.objectbox.query.OrderFlags
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TranscriptRepository @Inject constructor(
    private val boxStore: BoxStore
) : ITranscriptRepository {
    private val box: Box<Transcript> = boxStore.boxFor()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    override fun save(transcript: Transcript) = transcript.also { box.put(it) }
    override fun getById(id: Long) = box.get(id)
    override fun update(transcript: Transcript) = box.put(transcript)
    override fun delete(id: Long) = box.remove(id)
    override fun delete(transcript: Transcript) = box.remove(transcript)
    override fun deleteAll() = box.removeAll()
    override fun count() = box.count()
    override fun getAll() = box.query().order(Transcript_.timestamp, OrderFlags.DESCENDING).build().find()
    override fun getRecent(limit: Int) = box.query().order(Transcript_.timestamp, OrderFlags.DESCENDING).build().find(0, limit.toLong())
    override fun getByTimeRange(startTime: Long, endTime: Long) = box.query().between(Transcript_.timestamp, startTime, endTime).order(Transcript_.timestamp, OrderFlags.DESCENDING).build().find()
    override fun searchByText(searchText: String) = box.query().contains(Transcript_.text, searchText, io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE).order(Transcript_.timestamp, OrderFlags.DESCENDING).build().find()
    override fun toggleExcludeFromContext(id: Long) = box.get(id)?.let { it.excludeFromContext = !it.excludeFromContext; box.put(it) }
    override fun clearContext() = box.all.onEach { it.excludeFromContext = true }.let { box.put(it) }
    override fun getAllTranscripts(): Flow<List<Transcript>> = callbackFlow {
        val subscription = box.query().order(Transcript_.timestamp, OrderFlags.DESCENDING).build().subscribe().observer { trySend(it) }
        awaitClose { subscription.cancel() }
    }
    override fun getRecentTranscripts(limit: Int): Flow<List<Transcript>> = callbackFlow {
        val subscription = box.query().order(Transcript_.timestamp, OrderFlags.DESCENDING).build().subscribe().observer { trySend(it.take(limit)) }
        awaitClose { subscription.cancel() }
    }
    override fun getRecentContext(chunks: Int) = box.query()
        .equal(Transcript_.excludeFromContext, false)
        .order(Transcript_.timestamp, OrderFlags.DESCENDING)
        .build()
        .find(0, chunks.toLong())
        .takeIf { it.isNotEmpty() }
        ?.reversed()
        ?.joinToString("\n") { "[${dateFormat.format(Date(it.timestamp))}] ${it.text}" } ?: ""
}
