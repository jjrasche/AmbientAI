package com.ambientai.core.media

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeChannelService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "YouTubeChannelService"
    }

    suspend fun searchChannels(query: String, maxResults: Int = 10): List<YouTubeChannel> = withContext(Dispatchers.IO) {
        runCatching {
            Log.d(TAG, "Searching YouTube channels for: $query")
            val request = YoutubeDLRequest("ytsearch$maxResults:$query channel").apply {
                addOption("--dump-json")
                addOption("--skip-download")
                addOption("--flat-playlist")
            }
            val response = YoutubeDL.getInstance().execute(request, null)
            parseChannelResults(response.out)
        }.getOrElse { e ->
            Log.e(TAG, "Channel search failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun getChannelInfo(channelUrl: String): YouTubeChannel? = withContext(Dispatchers.IO) {
        runCatching {
            Log.d(TAG, "Fetching channel info: $channelUrl")
            val request = YoutubeDLRequest(channelUrl).apply {
                addOption("--dump-json")
                addOption("--playlist-items", "0")
                addOption("--skip-download")
            }
            val response = YoutubeDL.getInstance().execute(request, null)
            val json = JSONObject(response.out.trim().split("\n").first())
            YouTubeChannel(
                channelId = json.optString("channel_id", ""),
                channelName = json.optString("channel", json.optString("uploader", "Unknown")),
                description = json.optString("description", ""),
                thumbnailUrl = json.optString("thumbnail", ""),
                subscriberCount = json.optLong("channel_follower_count", 0)
            )
        }.getOrElse { e ->
            Log.e(TAG, "Failed to fetch channel info: ${e.message}")
            null
        }
    }

    private fun parseChannelResults(jsonOutput: String): List<YouTubeChannel> {
        val channelMap = mutableMapOf<String, YouTubeChannel>()
        jsonOutput.trim().split("\n").forEach { line ->
            if (line.isBlank()) return@forEach
            runCatching {
                val json = JSONObject(line)
                val channelId = json.optString("channel_id", "")
                val channelName = json.optString("channel", json.optString("uploader", ""))
                if (channelId.isNotEmpty() && channelName.isNotEmpty() && !channelMap.containsKey(channelId)) {
                    channelMap[channelId] = YouTubeChannel(
                        channelId = channelId,
                        channelName = channelName,
                        description = "",
                        thumbnailUrl = json.optString("thumbnail", ""),
                        subscriberCount = 0
                    )
                }
            }
        }
        return channelMap.values.toList()
    }
}

data class YouTubeChannel(
    val channelId: String,
    val channelName: String,
    val description: String,
    val thumbnailUrl: String,
    val subscriberCount: Long = 0
)
