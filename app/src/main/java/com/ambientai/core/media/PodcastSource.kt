package com.ambientai.core.media

import android.content.Context
import android.util.Log
import com.ambientai.data.repositories.IPodcastEpisodeRepository
import com.ambientai.data.repositories.IPodcastSubscriptionRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PodcastSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val episodeRepository: IPodcastEpisodeRepository,
    private val subscriptionRepository: IPodcastSubscriptionRepository,
    private val okHttpClient: OkHttpClient
) : MediaSource {
    companion object {
        private const val TAG = "PodcastSource"
    }

    override suspend fun search(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        val lowerQuery = query.lowercase()
        episodeRepository.getAll().filter {
            it.title.lowercase().contains(lowerQuery) || it.description.lowercase().contains(lowerQuery)
        }.map { episode ->
            val showTitle = subscriptionRepository.getById(episode.subscriptionId)?.title ?: ""
            MediaItem(
                id = episode.id,
                title = episode.title,
                creator = showTitle,
                sourceType = MediaSourceType.PODCAST_RSS,
                mediaType = MediaType.PODCAST,
                playbackUrl = episode.localFilePath ?: episode.audioUrl,
                duration = episode.duration,
                metadata = MediaMetadata(
                    podcastName = showTitle,
                    subscriptionId = episode.subscriptionId,
                    guid = episode.guid,
                    episodeNumber = episode.episodeNumber.takeIf { it > 0 },
                    episodeDescription = episode.description,
                    thumbnailPath = episode.artworkUrl,
                    publishDate = episode.publishDate
                )
            )
        }
    }

    override suspend fun getPlaybackUrl(item: MediaItem): String = withContext(Dispatchers.IO) {
        episodeRepository.getById(item.id)?.localFilePath ?: item.playbackUrl
    }

    override suspend fun download(item: MediaItem): String = withContext(Dispatchers.IO) {
        val podcastDir = File(context.filesDir, "podcasts").also { if (!it.exists()) it.mkdirs() }
        val fileName = "${item.id}_${System.currentTimeMillis()}.mp3"
        val localFile = File(podcastDir, fileName)

        episodeRepository.updateDownloadStatus(item.id, "downloading")

        val request = Request.Builder().url(item.playbackUrl).build()
        val response = okHttpClient.newCall(request).execute()
        if (!response.isSuccessful) {
            episodeRepository.updateDownloadStatus(item.id, "failed")
            throw IllegalStateException("Download failed: ${response.code}")
        }

        response.body?.byteStream()?.use { input ->
            FileOutputStream(localFile).use { output -> input.copyTo(output) }
        }

        episodeRepository.updateLocalFilePath(item.id, localFile.absolutePath)
        episodeRepository.updateDownloadStatus(item.id, "complete")

        Log.d(TAG, "Downloaded episode to: ${localFile.absolutePath}")
        localFile.absolutePath
    }

    override fun canHandle(item: MediaItem) = item.sourceType == MediaSourceType.PODCAST_RSS
}
