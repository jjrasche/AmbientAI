package com.ambientai.core.media

import com.ambientai.data.repositories.IYouTubeVideoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeSource @Inject constructor(
    private val videoRepository: IYouTubeVideoRepository,
    private val youtubeHandler: YouTubeHandler
) : MediaSource {
    override suspend fun search(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val lowerQuery = query.lowercase()
        videoRepository.getAll().filter {
            it.title.lowercase().contains(lowerQuery) || it.description.lowercase().contains(lowerQuery) || it.channelName.lowercase().contains(lowerQuery)
        }.map { video ->
            MediaItem(
                id = video.id,
                title = video.title,
                creator = video.channelName,
                sourceType = MediaSourceType.YOUTUBE,
                mediaType = MediaType.VIDEO,
                playbackUrl = video.localFilePath ?: "https://www.youtube.com/watch?v=${video.videoId}",
                duration = video.duration,
                metadata = MediaMetadata(
                    channelName = video.channelName,
                    subscriptionId = video.subscriptionId,
                    thumbnailPath = video.thumbnailUrl,
                    publishDate = video.publishDate,
                    episodeDescription = video.description
                )
            )
        }
    }

    override suspend fun getPlaybackUrl(item: MediaItem): String = withContext(Dispatchers.IO) {
        videoRepository.getById(item.id)?.localFilePath?.let { return@withContext it }
        val videoId = item.playbackUrl.substringAfter("v=").substringBefore("&")
        val audioFile = youtubeHandler.downloadAudio(item.playbackUrl, videoId)
        audioFile?.absolutePath?.also { videoRepository.updateLocalFilePath(item.id, it) }
            ?: throw IllegalStateException("Failed to download YouTube audio for ${item.title}")
    }

    override suspend fun download(item: MediaItem): String = withContext(Dispatchers.IO) {
        val videoId = item.playbackUrl.substringAfter("v=").substringBefore("&")
        videoRepository.updateDownloadStatus(item.id, "downloading")
        val audioFile = youtubeHandler.downloadAudio(item.playbackUrl, videoId)
        audioFile?.absolutePath?.also {
            videoRepository.updateLocalFilePath(item.id, it)
            videoRepository.updateDownloadStatus(item.id, "complete")
        } ?: run {
            videoRepository.updateDownloadStatus(item.id, "failed")
            throw IllegalStateException("Failed to download YouTube audio")
        }
    }

    override fun canHandle(item: MediaItem) = item.sourceType == MediaSourceType.YOUTUBE
}
