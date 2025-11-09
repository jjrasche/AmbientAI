package com.ambientai.data.repositories

import com.ambientai.data.entities.Narrative
import com.ambientai.data.entities.NarrativeType
import com.ambientai.data.entities.Narrative_
import io.objectbox.Box
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import io.objectbox.query.OrderFlags
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NarrativeRepository @Inject constructor(
    private val boxStore: BoxStore
) : INarrativeRepository {
    private val box: Box<Narrative> = boxStore.boxFor()

    override fun save(narrative: Narrative) = narrative.also { box.put(it) }
    override fun getById(id: Long) = box.get(id)
    override fun count() = box.count()
    override fun deleteAll() = box.removeAll()
    override fun getAll() = box.all
    override fun getByType(narrativeType: String) = box.query(Narrative_.narrativeType.equal(NarrativeType.valueOf(narrativeType).ordinal.toLong())).order(Narrative_.timestamp, OrderFlags.DESCENDING).build().find()
    override fun getLatest() = box.query().order(Narrative_.timestamp, OrderFlags.DESCENDING).build().findFirst()
    override fun getLatestByType(narrativeType: NarrativeType) = box.query(Narrative_.narrativeType.equal(narrativeType.ordinal.toLong())).order(Narrative_.timestamp, OrderFlags.DESCENDING).build().findFirst()
    override fun getRecent(limit: Int) = box.query().order(Narrative_.timestamp, OrderFlags.DESCENDING).build().find(0, limit.toLong())
    override fun getRecentByType(narrativeType: NarrativeType, limit: Int) = box.query(Narrative_.narrativeType.equal(narrativeType.ordinal.toLong())).order(Narrative_.timestamp, OrderFlags.DESCENDING).build().find(0, limit.toLong())
}
