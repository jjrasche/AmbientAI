package com.ambientai.data.repositories

import com.ambientai.data.entities.Transcript
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for Transcript entities.
 *
 * WHY INTERFACES?
 * 1. Testing: Inject FakeTranscriptRepository in tests
 * 2. Swappability: Could switch from ObjectBox → Room without changing callers
 * 3. Clear Contract: Shows exactly what operations are available
 * 4. Dependency Inversion: Depend on abstraction, not concrete implementation
 */
interface ITranscriptRepository {
    // CRUD operations
    fun save(transcript: Transcript): Transcript
    fun getById(id: Long): Transcript?
    fun update(transcript: Transcript)
    fun delete(id: Long)
    fun delete(transcript: Transcript)
    fun deleteAll()

    // Queries
    fun count(): Long
    fun getAll(): List<Transcript>
    fun getRecent(limit: Int): List<Transcript>
    fun getByTimeRange(startTime: Long, endTime: Long): List<Transcript>
    fun searchByText(searchText: String): List<Transcript>

    // Domain-specific operations
    fun toggleExcludeFromContext(id: Long)
    fun clearContext()
    fun getRecentContext(chunks: Int): String

    // Reactive streams
    fun getAllTranscripts(): Flow<List<Transcript>>
    fun getRecentTranscripts(limit: Int): Flow<List<Transcript>>
}
