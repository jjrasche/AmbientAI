package com.ambientai.data.repositories

import com.ambientai.AmbientAIApp
import com.ambientai.data.entities.Transcript
import com.ambientai.data.entities.Transcript_
import io.objectbox.Box
import io.objectbox.kotlin.boxFor
import io.objectbox.query.OrderFlags
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.text.SimpleDateFormat
import java.util.*

class TranscriptRepository {
    private val box: Box<Transcript> = AmbientAIApp.boxStore.boxFor()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun save(transcript: Transcript) = transcript.also { box.put(it) }
    fun getById(id: Long) = box.get(id)
    fun update(transcript: Transcript) = box.put(transcript)
    fun delete(id: Long) = box.remove(id)
    fun delete(transcript: Transcript) = box.remove(transcript)
    fun deleteAll() = box.removeAll()
    fun count() = box.count()

    fun getAll() = box.query().order(Transcript_.timestamp, OrderFlags.DESCENDING).build().find()
    fun getRecent(limit: Int) = box.query().order(Transcript_.timestamp, OrderFlags.DESCENDING).build().find(0, limit.toLong())
    fun getByTimeRange(startTime: Long, endTime: Long) = box.query().between(Transcript_.timestamp, startTime, endTime).order(Transcript_.timestamp, OrderFlags.DESCENDING).build().find()
    fun searchByText(searchText: String) = box.query().contains(Transcript_.text, searchText, io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE).order(Transcript_.timestamp, OrderFlags.DESCENDING).build().find()

    fun toggleExcludeFromContext(id: Long) = box.get(id)?.let { it.excludeFromContext = !it.excludeFromContext; box.put(it) }
    fun clearContext() = box.all.onEach { it.excludeFromContext = true }.let { box.put(it) }

    fun getAllTranscripts(): Flow<List<Transcript>> = callbackFlow {
        val subscription = box.query().order(Transcript_.timestamp, OrderFlags.DESCENDING).build().subscribe().observer { trySend(it) }
        awaitClose { subscription.cancel() }
    }

    fun getRecentTranscripts(limit: Int): Flow<List<Transcript>> = callbackFlow {
        val subscription = box.query().order(Transcript_.timestamp, OrderFlags.DESCENDING).build().subscribe().observer { trySend(it.take(limit)) }
        awaitClose { subscription.cancel() }
    }

    fun getRecentContext(chunks: Int) = box.query()
        .equal(Transcript_.excludeFromContext, false)
        .order(Transcript_.timestamp, OrderFlags.DESCENDING)
        .build()
        .find(0, chunks.toLong())
        .takeIf { it.isNotEmpty() }
        ?.reversed()
        ?.joinToString("\n") { "[${dateFormat.format(Date(it.timestamp))}] ${it.text}" } ?: ""
}
