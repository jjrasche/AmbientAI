package com.ambientai.data.repositories

import com.ambientai.data.entities.GoldenDataset
import com.ambientai.data.entities.GoldenDataset_
import io.objectbox.Box
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import io.objectbox.query.OrderFlags
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GoldenDatasetRepository @Inject constructor(private val boxStore: BoxStore) : IGoldenDatasetRepository {
    private val box: Box<GoldenDataset> = boxStore.boxFor()

    override fun save(dataset: GoldenDataset) = dataset.also { box.put(it) }
    override fun getById(id: Long) = box.get(id)
    override fun update(dataset: GoldenDataset) { box.put(dataset) }
    override fun delete(id: Long) { box.remove(id) }
    override fun deleteAll() = box.removeAll()
    override fun count() = box.count()
    override fun getAll() = box.query().order(GoldenDataset_.timestamp, OrderFlags.DESCENDING).build().find()
    override fun getRecent(limit: Int) = box.query().order(GoldenDataset_.timestamp, OrderFlags.DESCENDING).build().find(0, limit.toLong())
    override fun getByTimeRange(startTime: Long, endTime: Long) = box.query().between(GoldenDataset_.timestamp, startTime, endTime).order(GoldenDataset_.timestamp, OrderFlags.DESCENDING).build().find()
    override fun markAccurate(id: Long, accurate: Boolean) { box.get(id)?.let { it.wasAccurate = accurate; box.put(it) } }
    override fun getAllFlow(): Flow<List<GoldenDataset>> = callbackFlow {
        val subscription = box.query().order(GoldenDataset_.timestamp, OrderFlags.DESCENDING).build().subscribe().observer { trySend(it) }
        awaitClose { subscription.cancel() }
    }
}
