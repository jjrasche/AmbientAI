package com.ambientai.data.repositories

import android.content.Context
import android.util.Log
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

/**
 * Repository for CRUD operations on Transcript entities.
 * Exposes Flow for reactive UI updates.
 */
class TranscriptRepository(context: Context) {

    private val box: Box<Transcript> = AmbientAIApp.boxStore.boxFor()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    companion object {
        private const val TAG = "TranscriptRepository"
    }

    /**
     * Get all transcripts as a reactive Flow.
     * Automatically emits new data when database changes.
     */
    fun getAllTranscripts(): Flow<List<Transcript>> = callbackFlow {
        val query = box.query()
            .order(Transcript_.timestamp, OrderFlags.DESCENDING)
            .build()

        val subscription = query.subscribe().observer { data ->
            Log.d(TAG, "Flow emitting ${data.size} transcripts")
            trySend(data)
        }

        awaitClose {
            subscription.cancel()
            Log.d(TAG, "Flow collection cancelled")
        }
    }

    /**
     * Get recent N transcripts as a reactive Flow.
     * Automatically emits new data when database changes.
     */
    fun getRecentTranscripts(limit: Int): Flow<List<Transcript>> = callbackFlow {
        val query = box.query()
            .order(Transcript_.timestamp, OrderFlags.DESCENDING)
            .build()

        val subscription = query.subscribe().observer { data ->
            val limited = data.take(limit)
            Log.d(TAG, "Flow emitting ${limited.size} recent transcripts (limit: $limit)")
            trySend(limited)
        }

        awaitClose {
            subscription.cancel()
            Log.d(TAG, "Recent transcripts flow collection cancelled")
        }
    }

    fun save(transcript: Transcript): Transcript {
        box.put(transcript)
        Log.d(TAG, "Saved transcript ${transcript.id}")
        // No manual refresh needed - Flow auto-emits
        return transcript
    }

    fun getById(id: Long): Transcript? {
        return box.get(id)
    }

    fun getAll(): List<Transcript> {
        return box.query()
            .order(Transcript_.timestamp, OrderFlags.DESCENDING)
            .build()
            .find()
    }

    fun getRecent(limit: Int): List<Transcript> {
        return box.query()
            .order(Transcript_.timestamp, OrderFlags.DESCENDING)
            .build()
            .find(0, limit.toLong())
    }

    fun getByTimeRange(startTime: Long, endTime: Long): List<Transcript> {
        return box.query()
            .between(Transcript_.timestamp, startTime, endTime)
            .order(Transcript_.timestamp, OrderFlags.DESCENDING)
            .build()
            .find()
    }

    fun searchByText(searchText: String): List<Transcript> {
        return box.query()
            .contains(Transcript_.text, searchText, io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE)
            .order(Transcript_.timestamp, OrderFlags.DESCENDING)
            .build()
            .find()
    }

    /**
     * Toggle excludeFromContext for a transcript by ID.
     * Fetches fresh entity from database to avoid stale references.
     */
    fun toggleExcludeFromContext(id: Long) {
        val transcript = box.get(id) ?: run {
            Log.w(TAG, "Transcript $id not found for toggle")
            return
        }

        val oldValue = transcript.excludeFromContext
        transcript.excludeFromContext = !transcript.excludeFromContext
        box.put(transcript)

        Log.d(TAG, "Toggled transcript $id: excludeFromContext $oldValue -> ${transcript.excludeFromContext}")
        // Flow handles UI update automatically
    }

    fun update(transcript: Transcript) {
        Log.d(TAG, "Updating transcript ${transcript.id}: excludeFromContext=${transcript.excludeFromContext}")
        box.put(transcript)
        // Flow handles UI update automatically
    }

    fun delete(id: Long): Boolean {
        val result = box.remove(id)
        Log.d(TAG, "Deleted transcript $id: $result")
        return result
    }

    fun delete(transcript: Transcript) {
        box.remove(transcript)
        Log.d(TAG, "Deleted transcript ${transcript.id}")
    }

    fun deleteAll() {
        box.removeAll()
        Log.d(TAG, "Deleted all transcripts")
    }

    fun clearContext() {
        val transcripts = box.all
        transcripts.forEach { it.excludeFromContext = true }
        box.put(transcripts)
        Log.d(TAG, "Cleared context for ${transcripts.size} transcripts")
    }

    fun count(): Long {
        return box.count()
    }

    /**
     * Get recent transcripts formatted for LLM context.
     * Returns oldest first (chronological order) with timestamps.
     * Filters out transcripts marked as excludeFromContext.
     */
    fun getRecentContext(chunks: Int): String {
        val transcripts = box.query()
            .equal(Transcript_.excludeFromContext, false)
            .order(Transcript_.timestamp, OrderFlags.DESCENDING)
            .build()
            .find(0, chunks.toLong())

        if (transcripts.isEmpty()) return ""

        return transcripts.reversed().joinToString("\n") { transcript ->
            "[${dateFormat.format(Date(transcript.timestamp))}] ${transcript.text}"
        }
    }
}