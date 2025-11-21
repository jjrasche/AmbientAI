package com.ambientai.data.repositories

import com.ambientai.data.entities.PodcastEpisode
import com.ambientai.data.entities.PodcastEpisode_
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
class PodcastEpisodeRepository @Inject constructor(
    boxStore: BoxStore
) : IPodcastEpisodeRepository {
    private val box: Box<PodcastEpisode> = boxStore.boxFor()
    override fun save(episode: PodcastEpisode) = episode.also { box.put(it) }
    override fun getById(id: Long) = box.get(id)
    override fun getByGuid(guid: String) = box.query().equal(PodcastEpisode_.guid, guid, StringOrder.CASE_SENSITIVE).build().findFirst()
    override fun getBySubscription(subscriptionId: Long) = box.query().equal(PodcastEpisode_.subscriptionId, subscriptionId).order(PodcastEpisode_.publishDate, OrderFlags.DESCENDING).build().find()
    override fun getUnplayed() = box.query().equal(PodcastEpisode_.completed, false).order(PodcastEpisode_.publishDate, OrderFlags.DESCENDING).build().find()
    override fun getAll() = box.query().order(PodcastEpisode_.publishDate, OrderFlags.DESCENDING).build().find()
    override fun getAllFlow(): Flow<List<PodcastEpisode>> = callbackFlow {
        val subscription = box.query().order(PodcastEpisode_.publishDate, OrderFlags.DESCENDING).build().subscribe().observer { trySend(it) }
        awaitClose { subscription.cancel() }
    }
    override fun delete(id: Long) { box.remove(id) }
    override fun deleteBySubscription(subscriptionId: Long) { box.query().equal(PodcastEpisode_.subscriptionId, subscriptionId).build().remove() }
    override fun updatePlaybackPosition(id: Long, positionMs: Long) { box.get(id)?.let { it.playbackPosition = positionMs; box.put(it) } }
    override fun markCompleted(id: Long, completed: Boolean) { box.get(id)?.let { it.completed = completed; box.put(it) } }
    override fun updateDownloadStatus(id: Long, status: String, progress: Int) { box.get(id)?.let { it.downloadStatus = status; it.downloadProgress = progress; box.put(it) } }
    override fun updateLocalFilePath(id: Long, path: String) { box.get(id)?.let { it.localFilePath = path; box.put(it) } }
}
