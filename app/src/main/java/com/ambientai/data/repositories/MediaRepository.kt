package com.ambientai.data.repositories

import com.ambientai.data.entities.Media
import com.ambientai.data.entities.Media_
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
class MediaRepository @Inject constructor(
    private val boxStore: BoxStore
) : IMediaRepository {
    private val box: Box<Media> = boxStore.boxFor()
    override fun save(media: Media) = media.also { box.put(it) }
    override fun getById(id: Long) = box.get(id)
    override fun update(media: Media) { box.put(media) }
    override fun delete(id: Long) { box.remove(id) }
    override fun getAll() = box.query().order(Media_.createdDate, OrderFlags.DESCENDING).build().find()
    override fun getBySourceType(sourceType: String) = box.query().equal(Media_.sourceType, sourceType, io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE).order(Media_.createdDate, OrderFlags.DESCENDING).build().find()
    override fun getByMediaType(mediaType: String) = box.query().equal(Media_.mediaType, mediaType, io.objectbox.query.QueryBuilder.StringOrder.CASE_INSENSITIVE).order(Media_.createdDate, OrderFlags.DESCENDING).build().find()
    override fun updatePlaybackPosition(id: Long, positionMs: Long) { box.get(id)?.let { it.lastPlayedPosition = positionMs; box.put(it) } }
    override fun getAllMedia(): Flow<List<Media>> = callbackFlow {
        val subscription = box.query().order(Media_.createdDate, OrderFlags.DESCENDING).build().subscribe().observer { trySend(it) }
        awaitClose { subscription.cancel() }
    }
}
