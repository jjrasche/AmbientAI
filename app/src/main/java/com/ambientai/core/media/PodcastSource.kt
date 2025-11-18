package com.ambientai.core.media

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PodcastSource @Inject constructor() : MediaSource {
    companion object {
        private const val TAG = "PodcastSource"
    }

    override suspend fun search(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        Log.d(TAG, "Podcast search not yet implemented: $query")
        emptyList()
    }

    override suspend fun getPlaybackUrl(item: MediaItem) = item.playbackUrl

    override suspend fun download(item: MediaItem): String {
        Log.d(TAG, "Podcast download not yet implemented")
        throw UnsupportedOperationException("Podcast download not yet implemented")
    }

    override fun canHandle(item: MediaItem) = item.sourceType == MediaSourceType.PODCAST_RSS
}
