package com.ambientai.data.repositories

import android.content.Context
import com.ambientai.AmbientAIApp
import com.ambientai.data.entities.Transcript
import com.ambientai.data.entities.Transcript_
import io.objectbox.Box
import io.objectbox.kotlin.boxFor
import io.objectbox.query.OrderFlags
import com.ambientai.data.entities.MyObjectBox

/**
 * Repository for CRUD operations on Transcript entities.
 * Handles all database interactions for voice transcripts.
 */
class TranscriptRepository(context: Context) {

    private val box: Box<Transcript> = AmbientAIApp.boxStore.boxFor()

    /**
     * Save a new transcript or update existing one.
     * @return the saved transcript with updated ID
     */
    fun save(transcript: Transcript): Transcript {
        box.put(transcript)
        return transcript
    }

    /**
     * Get transcript by ID.
     * @return transcript or null if not found
     */
    fun getById(id: Long): Transcript? {
        return box.get(id)
    }

    /**
     * Get all transcripts ordered by timestamp (newest first).
     */
    fun getAll(): List<Transcript> {
        return box.query()
            .order(Transcript_.timestamp, OrderFlags.DESCENDING)
            .build()
            .find()
    }

    /**
     * Get the N most recent transcripts.
     * @param limit number of transcripts to retrieve
     * @return list of recent transcripts, newest first
     */
    fun getRecent(limit: Int): List<Transcript> {
        return box.query()
            .order(Transcript_.timestamp, OrderFlags.DESCENDING)
            .build()
            .find(0, limit.toLong())
    }

    /**
     * Get transcripts within a time range.
     * @param startTime start timestamp (inclusive)
     * @param endTime end timestamp (inclusive)
     */
    fun getByTimeRange(startTime: Long, endTime: Long): List<Transcript> {
        return box.query()
            .between(Transcript_.timestamp, startTime, endTime)
            .order(Transcript_.timestamp, OrderFlags.DESCENDING)
            .build()
            .find()
    }

    /**
     * Search transcripts by text content.
     * @param searchText text to search for (case-insensitive)
     */
    fun searchByText(searchText: String): List<Transcript> {
        return box.query()
            .contains(Transcript_.text, searchText, io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE)
            .order(Transcript_.timestamp, OrderFlags.DESCENDING)
            .build()
            .find()
    }

    /**
     * Update an existing transcript.
     */
    fun update(transcript: Transcript) {
        box.put(transcript)
    }

    /**
     * Delete transcript by ID.
     * @return true if deleted, false if not found
     */
    fun delete(id: Long): Boolean {
        return box.remove(id)
    }

    /**
     * Delete a transcript entity.
     */
    fun delete(transcript: Transcript) {
        box.remove(transcript)
    }

    /**
     * Delete all transcripts.
     */
    fun deleteAll() {
        box.removeAll()
    }

    /**
     * Get total count of transcripts.
     */
    fun count(): Long {
        return box.count()
    }

    /**
     * Close the ObjectBox store. Call when done with repository.
     */
    fun close() {
        box.store.close()
    }
}