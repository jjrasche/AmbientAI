package com.ambientai.core.media

import kotlinx.coroutines.flow.Flow

interface MediaLibrary {
    fun search(query: String): Flow<List<MediaItem>>
    fun getAll(): Flow<List<MediaItem>>
    fun getByPlatform(platform: Platform): Flow<List<MediaItem>>
    fun getByMediaType(mediaType: MediaType): Flow<List<MediaItem>>
    fun getUnplayed(): Flow<List<MediaItem>>
    fun getBySubscription(subscriptionId: Long, platform: Platform): Flow<List<MediaItem>>
    fun getRecent(limit: Int = 50): Flow<List<MediaItem>>
    fun getDownloaded(): Flow<List<MediaItem>>
    suspend fun getById(id: Long, platform: Platform): MediaItem?
    suspend fun updatePlaybackPosition(id: Long, platform: Platform, positionMs: Long)
    suspend fun markCompleted(id: Long, platform: Platform, completed: Boolean)
}
