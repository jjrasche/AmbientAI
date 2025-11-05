package com.ambientai.data.repositories

import com.ambientai.AmbientAIApp
import com.ambientai.data.entities.Narrative
import com.ambientai.data.entities.NarrativeType
import com.ambientai.data.entities.Narrative_
import io.objectbox.Box
import io.objectbox.kotlin.boxFor
import io.objectbox.query.OrderFlags

class NarrativeRepository {
    private val box: Box<Narrative> = AmbientAIApp.boxStore.boxFor()

    fun save(narrative: Narrative): Narrative {
        box.put(narrative)
        return narrative
    }

    fun getLatest(): Narrative? {
        return box.query()
            .order(Narrative_.timestamp, OrderFlags.DESCENDING)
            .build()
            .findFirst()
    }

    fun getLatestByType(narrativeType: NarrativeType): Narrative? {
        return box.query(Narrative_.narrativeType.equal(narrativeType.ordinal.toLong()))
            .order(Narrative_.timestamp, OrderFlags.DESCENDING)
            .build()
            .findFirst()
    }

    fun getById(id: Long): Narrative? {
        return box.get(id)
    }

    fun getRecent(limit: Int): List<Narrative> {
        return box.query()
            .order(Narrative_.timestamp, OrderFlags.DESCENDING)
            .build()
            .find(0, limit.toLong())
    }

    fun getRecentByType(narrativeType: NarrativeType, limit: Int): List<Narrative> {
        return box.query(Narrative_.narrativeType.equal(narrativeType.ordinal.toLong()))
            .order(Narrative_.timestamp, OrderFlags.DESCENDING)
            .build()
            .find(0, limit.toLong())
    }

    fun count(): Long {
        return box.count()
    }

    fun deleteAll() {
        box.removeAll()
    }
}