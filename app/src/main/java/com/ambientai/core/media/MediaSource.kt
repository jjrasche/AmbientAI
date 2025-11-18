package com.ambientai.core.media

interface MediaSource {
    suspend fun search(query: String): List<MediaItem>
    suspend fun getPlaybackUrl(item: MediaItem): String
    suspend fun download(item: MediaItem): String
    fun canHandle(item: MediaItem): Boolean
}
