package com.ambientai.data.repositories

import com.ambientai.data.entities.Narrative

interface INarrativeRepository {
    // CRUD operations
    fun save(narrative: Narrative): Narrative
    fun getById(id: Long): Narrative?
    fun deleteAll()

    // Queries
    fun getAll(): List<Narrative>
    fun count(): Long
    fun getByType(narrativeType: String): List<Narrative>
}
