package com.ambientai.core.media

import com.ambientai.data.entities.PodcastEpisode
import com.ambientai.data.entities.YouTubeVideo
import com.ambientai.data.repositories.IPodcastEpisodeRepository
import com.ambientai.data.repositories.IPodcastSubscriptionRepository
import com.ambientai.data.repositories.IYouTubeSubscriptionRepository
import com.ambientai.data.repositories.IYouTubeVideoRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaLibraryImpl @Inject constructor(
    private val podcastEpisodeRepository: IPodcastEpisodeRepository,
    private val podcastSubscriptionRepository: IPodcastSubscriptionRepository,
    private val youtubeVideoRepository: IYouTubeVideoRepository,
    private val youtubeSubscriptionRepository: IYouTubeSubscriptionRepository,
    private val localAudioSource: LocalAudioSource
) : MediaLibrary {

    override fun search(query: String): Flow<List<MediaItem>> = combine(
        podcastEpisodeRepository.getAllFlow().map { episodes -> episodes.filter { it.title.contains(query, ignoreCase = true) || it.description.contains(query, ignoreCase = true) }.map { it.toMediaItem() } },
        youtubeVideoRepository.getAllFlow().map { videos -> videos.filter { it.title.contains(query, ignoreCase = true) || it.description.contains(query, ignoreCase = true) }.map { it.toMediaItem() } }
    ) { podcastResults, youtubeResults ->
        val localResults = localAudioSource.findMatches(query).map { it.toMediaItem() }
        (podcastResults + youtubeResults + localResults).sortedByDescending { it.metadata.publishDate ?: 0 }
    }

    override fun getAll(): Flow<List<MediaItem>> = combine(
        podcastEpisodeRepository.getAllFlow().map { it.map { e -> e.toMediaItem() } },
        youtubeVideoRepository.getAllFlow().map { it.map { v -> v.toMediaItem() } }
    ) { podcasts, youtube ->
        val local = localAudioSource.getSongs().map { it.toMediaItem() }
        (podcasts + youtube + local).sortedByDescending { it.metadata.publishDate ?: 0 }
    }

    override fun getByPlatform(platform: Platform): Flow<List<MediaItem>> = when (platform) {
        Platform.PODCAST -> podcastEpisodeRepository.getAllFlow().map { it.map { e -> e.toMediaItem() } }
        Platform.YOUTUBE -> youtubeVideoRepository.getAllFlow().map { it.map { v -> v.toMediaItem() } }
        Platform.LOCAL -> youtubeVideoRepository.getAllFlow().map { localAudioSource.getSongs().map { it.toMediaItem() } }
    }

    override fun getByMediaType(mediaType: MediaType): Flow<List<MediaItem>> = getAll().map { items ->
        items.filter { it.mediaType == mediaType }
    }

    override fun getUnplayed(): Flow<List<MediaItem>> = combine(
        podcastEpisodeRepository.getAllFlow().map { it.filter { e -> !e.completed }.map { e -> e.toMediaItem() } },
        youtubeVideoRepository.getAllFlow().map { it.filter { v -> !v.completed }.map { v -> v.toMediaItem() } }
    ) { podcasts, youtube -> (podcasts + youtube).sortedByDescending { it.metadata.publishDate ?: 0 } }

    override fun getBySubscription(subscriptionId: Long, platform: Platform): Flow<List<MediaItem>> = when (platform) {
        Platform.PODCAST -> podcastEpisodeRepository.getAllFlow().map { it.filter { e -> e.subscriptionId == subscriptionId }.map { e -> e.toMediaItem() } }
        Platform.YOUTUBE -> youtubeVideoRepository.getAllFlow().map { it.filter { v -> v.subscriptionId == subscriptionId }.map { v -> v.toMediaItem() } }
        Platform.LOCAL -> youtubeVideoRepository.getAllFlow().map { emptyList() }
    }

    override fun getRecent(limit: Int): Flow<List<MediaItem>> = getAll().map { it.take(limit) }

    override fun getDownloaded(): Flow<List<MediaItem>> = combine(
        podcastEpisodeRepository.getAllFlow().map { it.filter { e -> e.downloadStatus == "complete" }.map { e -> e.toMediaItem() } },
        youtubeVideoRepository.getAllFlow().map { it.filter { v -> v.downloadStatus == "complete" }.map { v -> v.toMediaItem() } }
    ) { podcasts, youtube -> podcasts + youtube }

    override suspend fun getById(id: Long, platform: Platform): MediaItem? = when (platform) {
        Platform.PODCAST -> podcastEpisodeRepository.getById(id)?.toMediaItem()
        Platform.YOUTUBE -> youtubeVideoRepository.getById(id)?.toMediaItem()
        Platform.LOCAL -> null
    }

    override suspend fun updatePlaybackPosition(id: Long, platform: Platform, positionMs: Long) = when (platform) {
        Platform.PODCAST -> podcastEpisodeRepository.updatePlaybackPosition(id, positionMs)
        Platform.YOUTUBE -> youtubeVideoRepository.updatePlaybackPosition(id, positionMs)
        Platform.LOCAL -> Unit
    }

    override suspend fun markCompleted(id: Long, platform: Platform, completed: Boolean) = when (platform) {
        Platform.PODCAST -> podcastEpisodeRepository.markCompleted(id, completed)
        Platform.YOUTUBE -> youtubeVideoRepository.markCompleted(id, completed)
        Platform.LOCAL -> Unit
    }

    private fun PodcastEpisode.toMediaItem(): MediaItem {
        val showTitle = subscriptionId.let { podcastSubscriptionRepository.getById(it)?.title } ?: ""
        return MediaItem(
            id = id,
            title = title,
            creator = showTitle,
            sourceType = MediaSourceType.PODCAST_RSS,
            mediaType = MediaType.PODCAST,
            playbackUrl = localFilePath ?: audioUrl,
            duration = duration,
            metadata = MediaMetadata(
                podcastName = showTitle,
                subscriptionId = subscriptionId,
                guid = guid,
                episodeNumber = episodeNumber.takeIf { it > 0 },
                episodeDescription = description,
                thumbnailPath = artworkUrl,
                publishDate = publishDate
            )
        )
    }

    private fun YouTubeVideo.toMediaItem() = MediaItem(
        id = id,
        title = title,
        creator = channelName,
        sourceType = MediaSourceType.YOUTUBE,
        mediaType = MediaType.VIDEO,
        playbackUrl = localFilePath ?: "https://youtube.com/watch?v=$videoId",
        duration = duration,
        metadata = MediaMetadata(
            channelName = channelName,
            subscriptionId = subscriptionId,
            thumbnailPath = thumbnailUrl,
            publishDate = publishDate,
            episodeDescription = description
        )
    )
}
