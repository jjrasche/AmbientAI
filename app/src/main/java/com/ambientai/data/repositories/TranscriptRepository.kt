package com.ambientai.data.repositories

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
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
 * Exposes LiveData for reactive UI updates.
 */
class TranscriptRepository(context: Context) {

    private val box: Box<Transcript> = AmbientAIApp.boxStore.boxFor()
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    // LiveData for reactive updates
    private val _transcripts = MutableLiveData<List<Transcript>>()
    val transcripts: LiveData<List<Transcript>> = _transcripts

    private val _recentTranscripts = MutableLiveData<List<Transcript>>()
    val recentTranscripts: LiveData<List<Transcript>> = _recentTranscripts

    init {
        // Initialize with current data
        refreshLiveData()
    }

    private fun refreshLiveData() {
        _transcripts.postValue(getAll())
        _recentTranscripts.postValue(getRecent(20))
    }

    fun save(transcript: Transcript): Transcript {
        box.put(transcript)
        refreshLiveData()
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
        refreshLiveData()
    }

    fun delete(id: Long): Boolean {
        val result = box.remove(id)
        refreshLiveData()
        return result
    }

    fun delete(transcript: Transcript) {
        box.remove(transcript)
        refreshLiveData()
    }

    fun deleteAll() {
        box.removeAll()
        refreshLiveData()
    }

    fun clearContext() {
        val transcripts = box.all
        transcripts.forEach { it.excludeFromContext = true }
        box.put(transcripts)
        refreshLiveData()
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