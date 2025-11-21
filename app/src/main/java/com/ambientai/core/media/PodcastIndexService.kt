package com.ambientai.core.media

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PodcastIndexService @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "PodcastIndexService"
        private const val BASE_URL = "https://api.podcastindex.org/api/1.0"
        private const val API_KEY = ""  // TODO: Move to BuildConfig
        private const val API_SECRET = ""  // TODO: Move to BuildConfig
        private const val USER_AGENT = "AmbientAI/1.0"
    }

    suspend fun search(query: String, maxResults: Int = 10): List<PodcastSearchResult> = withContext(Dispatchers.IO) {
        if (API_KEY.isEmpty() || API_SECRET.isEmpty()) {
            Log.w(TAG, "Podcast Index API credentials not configured")
            return@withContext emptyList()
        }
        try {
            val url = "$BASE_URL/search/byterm?q=${java.net.URLEncoder.encode(query, "UTF-8")}&max=$maxResults"
            val response = executeRequest(url)
            response?.let { parseSearchResults(it) } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Search failed: ${e.message}", e)
            emptyList()
        }
    }

    suspend fun getShowByFeedUrl(feedUrl: String): PodcastShow? = withContext(Dispatchers.IO) {
        if (API_KEY.isEmpty() || API_SECRET.isEmpty()) return@withContext null
        try {
            val url = "$BASE_URL/podcasts/byfeedurl?url=${java.net.URLEncoder.encode(feedUrl, "UTF-8")}"
            val response = executeRequest(url)
            response?.let { parseShow(it.optJSONObject("feed")) }
        } catch (e: Exception) {
            Log.e(TAG, "Get show failed: ${e.message}", e)
            null
        }
    }

    suspend fun getEpisodes(feedId: Long, maxResults: Int = 20): List<PodcastEpisodeResult> = withContext(Dispatchers.IO) {
        if (API_KEY.isEmpty() || API_SECRET.isEmpty()) return@withContext emptyList()
        try {
            val url = "$BASE_URL/episodes/byfeedid?id=$feedId&max=$maxResults"
            val response = executeRequest(url)
            response?.let { parseEpisodes(it) } ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Get episodes failed: ${e.message}", e)
            emptyList()
        }
    }

    private fun executeRequest(url: String): JSONObject? {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val authHash = sha1("$API_KEY$API_SECRET$timestamp")
        val request = Request.Builder()
            .url(url)
            .addHeader("X-Auth-Date", timestamp)
            .addHeader("X-Auth-Key", API_KEY)
            .addHeader("Authorization", authHash)
            .addHeader("User-Agent", USER_AGENT)
            .build()
        val response = okHttpClient.newCall(request).execute()
        return if (response.isSuccessful) {
            response.body?.string()?.let { JSONObject(it) }
        } else {
            Log.e(TAG, "API error: ${response.code} - ${response.message}")
            null
        }
    }

    private fun sha1(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-1").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun parseSearchResults(json: JSONObject): List<PodcastSearchResult> {
        val feeds = json.optJSONArray("feeds") ?: return emptyList()
        return (0 until feeds.length()).mapNotNull { i ->
            val feed = feeds.getJSONObject(i)
            PodcastSearchResult(
                id = feed.optLong("id"),
                title = feed.optString("title", ""),
                author = feed.optString("author", ""),
                description = feed.optString("description", ""),
                feedUrl = feed.optString("url", ""),
                artworkUrl = feed.optString("artwork", null),
                websiteUrl = feed.optString("link", null),
                episodeCount = feed.optInt("episodeCount", 0),
                lastUpdateTime = feed.optLong("lastUpdateTime", 0)
            )
        }
    }

    private fun parseShow(json: JSONObject?): PodcastShow? {
        json ?: return null
        return PodcastShow(
            id = json.optLong("id"),
            title = json.optString("title", ""),
            author = json.optString("author", ""),
            description = json.optString("description", ""),
            feedUrl = json.optString("url", ""),
            artworkUrl = json.optString("artwork", null),
            websiteUrl = json.optString("link", null)
        )
    }

    private fun parseEpisodes(json: JSONObject): List<PodcastEpisodeResult> {
        val items = json.optJSONArray("items") ?: return emptyList()
        return (0 until items.length()).mapNotNull { i ->
            val item = items.getJSONObject(i)
            PodcastEpisodeResult(
                id = item.optLong("id"),
                title = item.optString("title", ""),
                description = item.optString("description", ""),
                audioUrl = item.optString("enclosureUrl", ""),
                duration = item.optLong("duration", 0) * 1000,
                publishTime = item.optLong("datePublished", 0) * 1000,
                episodeNumber = item.optInt("episode", 0),
                chaptersUrl = item.optString("chaptersUrl", null)
            )
        }
    }
}

data class PodcastSearchResult(
    val id: Long,
    val title: String,
    val author: String,
    val description: String,
    val feedUrl: String,
    val artworkUrl: String?,
    val websiteUrl: String?,
    val episodeCount: Int,
    val lastUpdateTime: Long
)

data class PodcastShow(
    val id: Long,
    val title: String,
    val author: String,
    val description: String,
    val feedUrl: String,
    val artworkUrl: String?,
    val websiteUrl: String?
)

data class PodcastEpisodeResult(
    val id: Long,
    val title: String,
    val description: String,
    val audioUrl: String,
    val duration: Long,
    val publishTime: Long,
    val episodeNumber: Int,
    val chaptersUrl: String?
)
