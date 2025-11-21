package com.ambientai.data.repositories

import com.ambientai.data.entities.PodcastEpisode
import kotlinx.coroutines.flow.Flow

interface IPodcastEpisodeRepository {
    fun save(episode: PodcastEpisode): PodcastEpisode
    fun getById(id: Long): PodcastEpisode?
    fun getByGuid(guid: String): PodcastEpisode?
    fun getBySubscription(subscriptionId: Long): List<PodcastEpisode>
    fun getUnplayed(): List<PodcastEpisode>
    fun getAll(): List<PodcastEpisode>
    fun getAllFlow(): Flow<List<PodcastEpisode>>
    fun delete(id: Long)
    fun deleteBySubscription(subscriptionId: Long)
    fun updatePlaybackPosition(id: Long, positionMs: Long)
    fun markCompleted(id: Long, completed: Boolean)
    fun updateDownloadStatus(id: Long, status: String, progress: Int = 0)
    fun updateLocalFilePath(id: Long, path: String)
}
