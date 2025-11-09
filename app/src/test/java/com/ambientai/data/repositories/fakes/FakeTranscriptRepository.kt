package com.ambientai.data.repositories.fakes

import com.ambientai.data.entities.Transcript
import com.ambientai.data.repositories.ITranscriptRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Fake implementation of ITranscriptRepository for testing.
 *
 * Provides an in-memory implementation of transcript storage for unit tests.
 */
class FakeTranscriptRepository : ITranscriptRepository {
    private val transcripts = mutableMapOf<Long, Transcript>()
    private var nextId = 1L

    override fun save(transcript: Transcript): Transcript {
        val savedTranscript = if (transcript.id == 0L) {
            transcript.copy(id = nextId++)
        } else {
            transcript
        }
        transcripts[savedTranscript.id] = savedTranscript
        return savedTranscript
    }

    override fun getById(id: Long): Transcript? = transcripts[id]

    override fun update(transcript: Transcript) {
        transcripts[transcript.id] = transcript
    }

    override fun delete(id: Long) {
        transcripts.remove(id)
    }

    override fun delete(transcript: Transcript) {
        transcripts.remove(transcript.id)
    }

    override fun deleteAll() {
        transcripts.clear()
    }

    override fun count(): Long = transcripts.size.toLong()

    override fun getAll(): List<Transcript> =
        transcripts.values.sortedByDescending { it.timestamp }

    override fun getRecent(limit: Int): List<Transcript> =
        transcripts.values.sortedByDescending { it.timestamp }.take(limit)

    override fun getByTimeRange(startTime: Long, endTime: Long): List<Transcript> =
        transcripts.values.filter { it.timestamp in startTime..endTime }
            .sortedByDescending { it.timestamp }

    override fun searchByText(searchText: String): List<Transcript> =
        transcripts.values.filter { it.text.contains(searchText, ignoreCase = true) }
            .sortedByDescending { it.timestamp }

    override fun toggleExcludeFromContext(id: Long) {
        transcripts[id]?.let {
            transcripts[id] = it.copy(excludeFromContext = !it.excludeFromContext)
        }
    }

    override fun clearContext() {
        transcripts.values.forEach { transcript ->
            transcripts[transcript.id] = transcript.copy(excludeFromContext = true)
        }
    }

    override fun getAllTranscripts(): Flow<List<Transcript>> =
        flowOf(transcripts.values.sortedByDescending { it.timestamp })

    override fun getRecentTranscripts(limit: Int): Flow<List<Transcript>> =
        flowOf(transcripts.values.sortedByDescending { it.timestamp }.take(limit))

    override fun getRecentContext(chunks: Int): String {
        val contextTranscripts = transcripts.values
            .filter { !it.excludeFromContext }
            .sortedByDescending { it.timestamp }
            .take(chunks)
            .reversed()

        return contextTranscripts.joinToString("\n") { "[${it.timestamp}] ${it.text}" }
    }

    // Helper methods for testing
    fun clear() {
        transcripts.clear()
        nextId = 1L
    }

    fun addTranscript(transcript: Transcript) {
        save(transcript)
    }
}
