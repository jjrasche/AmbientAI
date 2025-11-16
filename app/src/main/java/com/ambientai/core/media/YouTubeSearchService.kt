package com.ambientai.core.media

import android.content.Context
import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeSearchService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "YouTubeSearchService"
        private const val DEFAULT_MAX_RESULTS = 10
    }
    suspend fun search(query: String, maxResults: Int = DEFAULT_MAX_RESULTS): YouTubeSearchResponse? = withContext(Dispatchers.IO) { runCatching { Log.d(TAG, "Searching YouTube for: $query (max=$maxResults)"); val request = YoutubeDLRequest("ytsearch$maxResults:$query").apply { addOption("--dump-json"); addOption("--skip-download"); addOption("--flat-playlist") }; val response = YoutubeDL.getInstance().execute(request, null); val results = parseSearchResults(response.out); YouTubeSearchResponse(results = results, query = query, totalResults = results.size) }.getOrElse { e -> Log.e(TAG, "Search failed for query: $query", e); null } }
    private fun parseSearchResults(jsonOutput: String): List<YouTubeSearchResult> = runCatching { val lines = jsonOutput.trim().split("\n"); lines.mapNotNull { line -> if (line.isBlank()) return@mapNotNull null; try { val json = JSONObject(line); YouTubeSearchResult(videoId = json.optString("id", ""), title = json.optString("title", "Untitled"), channelName = json.optString("channel", json.optString("uploader", "Unknown")), thumbnailUrl = json.optString("thumbnail", ""), duration = json.optLong("duration", 0) * 1000, viewCount = json.optLong("view_count", 0), publishedDate = json.optLong("timestamp", 0) * 1000, description = json.optString("description", "")) } catch (e: Exception) { Log.w(TAG, "Failed to parse search result: ${e.message}"); null } } }.getOrDefault(emptyList())
}
