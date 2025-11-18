package com.ambientai.core.media

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeSource @Inject constructor(
    private val searchService: YouTubeSearchService,
    private val youtubeHandler: YouTubeHandler
) : MediaSource {
    override suspend fun search(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val searchResults = searchService.search(query, maxResults = 10)
        searchResults?.results?.map { result ->
            MediaItem(
                id = 0,
                title = result.title,
                creator = result.channelName,
                sourceType = MediaSourceType.YOUTUBE,
                mediaType = MediaType.MUSIC,
                playbackUrl = "https://www.youtube.com/watch?v=${result.videoId}",
                duration = result.duration,
                metadata = MediaMetadata(
                    channelName = result.channelName,
                    thumbnailPath = result.thumbnailUrl,
                    publishDate = result.publishedDate
                )
            )
        } ?: emptyList()
    }

    override suspend fun getPlaybackUrl(item: MediaItem): String = withContext(Dispatchers.IO) {
        val videoId = item.playbackUrl.substringAfter("v=")
        val audioFile = youtubeHandler.downloadAudio(item.playbackUrl, videoId)
        audioFile?.absolutePath ?: throw IllegalStateException("Failed to download YouTube audio for ${item.title}")
    }

    override suspend fun download(item: MediaItem): String {
        val videoId = item.playbackUrl.substringAfter("v=")
        val audioFile = youtubeHandler.downloadAudio(item.playbackUrl, videoId)
        return audioFile?.absolutePath ?: throw IllegalStateException("Failed to download YouTube audio")
    }

    override fun canHandle(item: MediaItem) = item.sourceType == MediaSourceType.YOUTUBE
}
