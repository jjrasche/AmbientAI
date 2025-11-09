package com.ambientai.data.repositories

import com.ambientai.data.entities.Narrative
import com.ambientai.data.entities.NarrativeType

interface INarrativeRepository {
    // CRUD operations
    fun save(narrative: Narrative): Narrative
    fun getById(id: Long): Narrative?
    fun deleteAll()

    // Queries
    fun getAll(): List<Narrative>
    fun count(): Long
    fun getByType(narrativeType: String): List<Narrative>
    fun getLatest(): Narrative?
    fun getLatestByType(narrativeType: NarrativeType): Narrative?
    fun getRecent(limit: Int): List<Narrative>
    fun getRecentByType(narrativeType: NarrativeType, limit: Int): List<Narrative>
}
