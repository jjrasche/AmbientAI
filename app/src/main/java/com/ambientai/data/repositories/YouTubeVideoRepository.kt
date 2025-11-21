package com.ambientai.data.repositories

import com.ambientai.data.entities.YouTubeVideo
import com.ambientai.data.entities.YouTubeVideo_
import io.objectbox.Box
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import io.objectbox.query.OrderFlags
import io.objectbox.query.QueryBuilder.StringOrder
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeVideoRepository @Inject constructor(boxStore: BoxStore) : IYouTubeVideoRepository {
    private val box: Box<YouTubeVideo> = boxStore.boxFor()
    override fun save(video: YouTubeVideo) = video.also { box.put(it) }
    override fun getById(id: Long) = box.get(id)
    override fun getByVideoId(videoId: String) = box.query().equal(YouTubeVideo_.videoId, videoId, StringOrder.CASE_SENSITIVE).build().findFirst()
    override fun getBySubscription(subscriptionId: Long) = box.query().equal(YouTubeVideo_.subscriptionId, subscriptionId).order(YouTubeVideo_.publishDate, OrderFlags.DESCENDING).build().find()
    override fun getUnplayed() = box.query().equal(YouTubeVideo_.completed, false).order(YouTubeVideo_.publishDate, OrderFlags.DESCENDING).build().find()
    override fun getAll() = box.query().order(YouTubeVideo_.publishDate, OrderFlags.DESCENDING).build().find()
    override fun getAllFlow(): Flow<List<YouTubeVideo>> = callbackFlow {
        val subscription = box.query().order(YouTubeVideo_.publishDate, OrderFlags.DESCENDING).build().subscribe().observer { trySend(it) }
        awaitClose { subscription.cancel() }
    }
    override fun delete(id: Long) { box.remove(id) }
    override fun deleteBySubscription(subscriptionId: Long) { box.query().equal(YouTubeVideo_.subscriptionId, subscriptionId).build().remove() }
    override fun updatePlaybackPosition(id: Long, positionMs: Long) { box.get(id)?.let { it.playbackPosition = positionMs; box.put(it) } }
    override fun markCompleted(id: Long, completed: Boolean) { box.get(id)?.let { it.completed = completed; box.put(it) } }
    override fun updateDownloadStatus(id: Long, status: String) { box.get(id)?.let { it.downloadStatus = status; box.put(it) } }
    override fun updateLocalFilePath(id: Long, path: String) { box.get(id)?.let { it.localFilePath = path; box.put(it) } }
}
