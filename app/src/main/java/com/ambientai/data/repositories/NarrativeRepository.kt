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

    fun save(narrative: Narrative) = narrative.also { box.put(it) }
    fun getById(id: Long) = box.get(id)
    fun count() = box.count()
    fun deleteAll() = box.removeAll()

    fun getLatest() = box.query().order(Narrative_.timestamp, OrderFlags.DESCENDING).build().findFirst()
    fun getLatestByType(narrativeType: NarrativeType) = box.query(Narrative_.narrativeType.equal(narrativeType.ordinal.toLong())).order(Narrative_.timestamp, OrderFlags.DESCENDING).build().findFirst()
    fun getRecent(limit: Int) = box.query().order(Narrative_.timestamp, OrderFlags.DESCENDING).build().find(0, limit.toLong())
    fun getRecentByType(narrativeType: NarrativeType, limit: Int) = box.query(Narrative_.narrativeType.equal(narrativeType.ordinal.toLong())).order(Narrative_.timestamp, OrderFlags.DESCENDING).build().find(0, limit.toLong())
}
