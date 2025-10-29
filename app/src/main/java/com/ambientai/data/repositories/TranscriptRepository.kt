package com.ambientai.data.repositories

import android.content.Context
import com.ambientai.AmbientAIApp
import com.ambientai.data.entities.Transcript
import com.ambientai.data.entities.Transcript_
import io.objectbox.Box
import io.objectbox.kotlin.boxFor
import io.objectbox.query.OrderFlags

/**
 * Repository for CRUD operations on Transcript entities.
 */
class TranscriptRepository(context: Context) {

    private val box: Box<Transcript> = AmbientAIApp.boxStore.boxFor()

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

    fun count(): Long {
        return box.count()
    }

    fun close() {
        box.store.close()
    }
}