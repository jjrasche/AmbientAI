package com.ambientai.data.repositories

import android.content.Context
import com.ambientai.AmbientAIApp
import com.ambientai.data.entities.Transcript
import com.ambientai.data.entities.Transcript_
import io.objectbox.Box
import io.objectbox.kotlin.boxFor
import io.objectbox.query.OrderFlags
import java.text.SimpleDateFormat
import java.util.*

/**
 * Repository for CRUD operations on Transcript entities.
 */
class TranscriptRepository(context: Context) {

    private val box: Box<Transcript> = AmbientAIApp.boxStore.boxFor()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun save(transcript: Transcript): Transcript {
        box.put(transcript)
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

    fun update(transcript: Transcript) {
        box.put(transcript)
    }

    fun delete(id: Long): Boolean {
        return box.remove(id)
    }

    fun delete(transcript: Transcript) {
        box.remove(transcript)
    }

    fun deleteAll() {
        box.removeAll()
    }

    fun clearContext() {
        val transcripts = box.all
        transcripts.forEach { it.excludeFromContext = true }
        box.put(transcripts)
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