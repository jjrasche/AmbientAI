package com.ambientai.data.repositories

import com.ambientai.data.entities.MediaHistory
import com.ambientai.data.entities.MediaHistory_
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
class MediaHistoryRepository @Inject constructor(private val boxStore: BoxStore) : IMediaHistoryRepository {
    private val box: Box<MediaHistory> = boxStore.boxFor()
    override fun save(history: MediaHistory) = history.also { box.put(it) }
    override fun getById(id: Long) = box.get(id)
    override fun delete(id: Long) { box.remove(id) }
    override fun getAll() = box.query().order(MediaHistory_.timestamp, OrderFlags.DESCENDING).build().find()
    override fun getByMediaType(mediaType: String) = box.query(MediaHistory_.mediaType.equal(mediaType)).order(MediaHistory_.timestamp, OrderFlags.DESCENDING).build().find()
    override fun getRecent(limit: Int) = box.query().order(MediaHistory_.timestamp, OrderFlags.DESCENDING).build().find(0, limit.toLong())
    override fun getByTimeRange(startTime: Long, endTime: Long) = box.query().between(MediaHistory_.timestamp, startTime, endTime).order(MediaHistory_.timestamp, OrderFlags.DESCENDING).build().find()
    override fun getAllHistory(): Flow<List<MediaHistory>> = callbackFlow { val subscription = box.query().order(MediaHistory_.timestamp, OrderFlags.DESCENDING).build().subscribe().observer { trySend(it) }; awaitClose { subscription.cancel() } }
}
