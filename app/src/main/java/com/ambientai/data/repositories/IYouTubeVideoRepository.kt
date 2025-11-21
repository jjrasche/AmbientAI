package com.ambientai.data.repositories

import com.ambientai.data.entities.YouTubeVideo
import kotlinx.coroutines.flow.Flow

interface IYouTubeVideoRepository {
    fun save(video: YouTubeVideo): YouTubeVideo
    fun getById(id: Long): YouTubeVideo?
    fun getByVideoId(videoId: String): YouTubeVideo?
    fun getBySubscription(subscriptionId: Long): List<YouTubeVideo>
    fun getUnplayed(): List<YouTubeVideo>
    fun getAll(): List<YouTubeVideo>
    fun getAllFlow(): Flow<List<YouTubeVideo>>
    fun delete(id: Long)
    fun deleteBySubscription(subscriptionId: Long)
    fun updatePlaybackPosition(id: Long, positionMs: Long)
    fun markCompleted(id: Long, completed: Boolean)
    fun updateDownloadStatus(id: Long, status: String)
    fun updateLocalFilePath(id: Long, path: String)
}
