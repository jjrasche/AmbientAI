package com.ambientai.core.media

import android.content.Context
import android.util.Log
import com.ambientai.data.entities.YouTubeSubscription
import com.ambientai.data.entities.YouTubeVideo
import com.ambientai.data.repositories.IYouTubeSubscriptionRepository
import com.ambientai.data.repositories.IYouTubeVideoRepository
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeSubscriptionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val subscriptionRepository: IYouTubeSubscriptionRepository,
    private val videoRepository: IYouTubeVideoRepository
) : BaseSubscriptionManager<YouTubeSubscription>() {

    override val platform = Platform.YOUTUBE
    override val tag = "YouTubeSubManager"

    companion object {
        private const val MAX_VIDEOS_PER_REFRESH = 20
        @Volatile private var ytdlInitialized = false
    }

    private suspend fun ensureYtdlInitialized() {
        if (!ytdlInitialized) {
            withContext(Dispatchers.IO) {
                synchronized(this) {
                    if (!ytdlInitialized) {
                        try {
                            YoutubeDL.getInstance().init(context)
                            Log.d(tag, "YoutubeDL initialized for subscription manager")
                            try {
                                YoutubeDL.getInstance().updateYoutubeDL(context, YoutubeDL.UpdateChannel.NIGHTLY)
                                Log.d(tag, "yt-dlp updated to NIGHTLY")
                            } catch (e: Exception) {
                                Log.w(tag, "yt-dlp update failed (using bundled): ${e.message}")
                            }
                            ytdlInitialized = true
                        } catch (e: Exception) {
                            Log.e(tag, "Failed to initialize YoutubeDL: ${e.message}")
                        }
                    }
                }
            }
        }
    }

    override fun getSubscriptions(): Flow<List<YouTubeSubscription>> = subscriptionRepository.getAllFlow()

    // Platform-specific: How to fetch subscription info
    override suspend fun fetchSubscriptionInfo(identifier: String): SubscriptionInfo? = withContext(Dispatchers.IO) {
        ensureYtdlInitialized()
        runCatching {
            // Construct full URL from channel ID if needed
            val url = if (identifier.startsWith("http")) identifier
                     else "https://www.youtube.com/channel/${extractChannelId(identifier)}"
            Log.d(tag, "Fetching subscription info from: $url")
            val request = YoutubeDLRequest(url).apply {
                addOption("--dump-json")
                addOption("--playlist-items", "1")  // Get first video to extract channel info
                addOption("--skip-download")
            }
            val response = YoutubeDL.getInstance().execute(request, null)
            Log.d(tag, "yt-dlp exitCode: ${response.exitCode}")
            Log.d(tag, "yt-dlp stdout length: ${response.out?.length ?: 0}")
            Log.d(tag, "yt-dlp stderr: ${response.err?.take(500) ?: "null"}")
            if (response.out.isNullOrBlank()) {
                Log.e(tag, "yt-dlp returned empty output!")
                return@withContext null
            }
            val json = JSONObject(response.out.trim().split("\n").first())
            SubscriptionInfo(
                identifier = json.optString("channel_id", extractChannelId(identifier)),
                title = json.optString("channel", json.optString("uploader", "Unknown")),
                description = json.optString("description", ""),
                thumbnailUrl = json.optString("thumbnail", "")
            )
        }.onFailure { e ->
            Log.e(tag, "yt-dlp error for $identifier: ${e.message}", e)
        }.getOrNull()
    }

    // Platform-specific: How to fetch content
    override suspend fun fetchContentInfo(identifier: String): List<ContentInfo> = withContext(Dispatchers.IO) {
        ensureYtdlInitialized()
        runCatching {
            // Construct full URL from channel ID if needed
            val url = if (identifier.startsWith("http")) identifier
                     else "https://www.youtube.com/channel/${extractChannelId(identifier)}"
            val request = YoutubeDLRequest(url).apply {
                addOption("--dump-json")
                addOption("--flat-playlist")
                addOption("--playlist-end", MAX_VIDEOS_PER_REFRESH.toString())
                addOption("--skip-download")
            }
            val response = YoutubeDL.getInstance().execute(request, null)
            response.out.trim().split("\n").mapNotNull { line ->
                if (line.isBlank()) return@mapNotNull null
                runCatching {
                    val json = JSONObject(line)
                    ContentInfo(
                        identifier = json.optString("id", ""),
                        title = json.optString("title", "Untitled"),
                        description = json.optString("description", ""),
                        thumbnailUrl = json.optString("thumbnail", ""),
                        duration = json.optLong("duration", 0) * 1000,
                        publishDate = json.optLong("timestamp", 0) * 1000
                    )
                }.getOrNull()
            }
        }.getOrElse { emptyList() }
    }

    // Platform-specific: Entity creation
    override fun createAndSaveSubscription(info: SubscriptionInfo) = subscriptionRepository.save(
        YouTubeSubscription(
            channelId = info.identifier,
            channelName = info.title,
            description = info.description,
            thumbnailUrl = info.thumbnailUrl
        )
    )

    override fun saveNewContent(subscription: YouTubeSubscription, content: List<ContentInfo>): Int {
        val existingVideoIds = videoRepository.getBySubscription(subscription.id).map { it.videoId }.toSet()
        return content.filterNot { it.identifier in existingVideoIds }.map { item ->
            videoRepository.save(YouTubeVideo(
                subscriptionId = subscription.id,
                videoId = item.identifier,
                title = item.title,
                description = item.description,
                channelName = subscription.channelName,
                thumbnailUrl = item.thumbnailUrl,
                duration = item.duration,
                publishDate = item.publishDate
            ))
        }.size
    }

    override fun deleteContent(subscriptionId: Long) = videoRepository.deleteBySubscription(subscriptionId)
    override fun deleteSubscription(subscriptionId: Long) = subscriptionRepository.delete(subscriptionId)

    // Platform-specific: Repository access
    override fun getByIdentifier(identifier: String): YouTubeSubscription? {
        val channelId = extractChannelId(identifier)
        return subscriptionRepository.getByChannelId(channelId)
    }
    override fun getAllSubscriptionsList() = subscriptionRepository.getAll()
    override fun getSubscriptionById(id: Long) = subscriptionRepository.getById(id)
    override fun getSubscriptionIdentifier(subscription: YouTubeSubscription) =
        "https://www.youtube.com/channel/${subscription.channelId}"

    // Helper to extract channel ID from various URL formats
    private fun extractChannelId(identifier: String): String = when {
        identifier.contains("/channel/") -> identifier.substringAfter("/channel/").substringBefore("/").substringBefore("?")
        identifier.contains("/@") -> identifier.substringAfter("/@").substringBefore("/").substringBefore("?")
        identifier.startsWith("UC") && identifier.length == 24 -> identifier
        else -> identifier
    }
}
