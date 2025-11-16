package com.ambientai.core.media

import android.util.Log
import com.ambientai.core.ui.UiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaWorkflowHandler @Inject constructor(
    private val youtubeSearch: YouTubeSearchService,
    private val mediaIntelligence: MediaIntelligenceManager,
    private val uiService: UiService,
    private val semanticSearch: MediaSemanticSearch
) {
    companion object { private const val TAG = "MediaWorkflowHandler" }
    suspend fun execute(actionName: String, input: JSONObject) = when (actionName) { "media.searchAndSelect" -> searchAndSelect(input); "media.download" -> download(input); "media.searchLibrary" -> searchLibrary(input); else -> errorResult("Unknown action: $actionName") }
    private fun successResult(data: Map<String, Any?> = emptyMap()) = JSONObject().apply { put("success", true); data.forEach { (k, v) -> put(k, v) } }
    private fun errorResult(message: String) = JSONObject().apply { put("success", false); put("error", message) }
    private suspend fun searchAndSelect(input: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val query = input.optString("query", null) ?: return@withContext errorResult("query required")
        val maxResults = input.optInt("max_results", 5)
        Log.d(TAG, "🔍 SEARCHING YOUTUBE: $query (max=$maxResults)")
        val searchResponse = youtubeSearch.search(query, maxResults) ?: return@withContext errorResult("YouTube search failed")
        if (searchResponse.results.isEmpty()) return@withContext errorResult("No results found")
        val options = searchResponse.results.map { "${it.title} — ${it.channelName} (${formatDuration(it.duration)})" }
        val choiceInput = JSONObject().apply { put("title", "Select Video"); put("message", "Search: $query"); put("options", JSONArray(options)); put("timeout_ms", 300000L) }
        val choiceResult = uiService.executeAsync("ui.awaitChoice", choiceInput)
        if (!choiceResult.optBoolean("success", false)) return@withContext errorResult("User did not select a video")
        val selectedIndex = choiceResult.optInt("selected_index", -1)
        if (selectedIndex !in searchResponse.results.indices) return@withContext errorResult("Invalid selection")
        val selected = searchResponse.results[selectedIndex]
        Log.d(TAG, "✓ USER SELECTED: ${selected.title} (id=${selected.videoId})")
        successResult(mapOf("video_id" to selected.videoId, "title" to selected.title, "channel" to selected.channelName, "duration_ms" to selected.duration, "url" to "https://youtube.com/watch?v=${selected.videoId}"))
    }
    private suspend fun download(input: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val videoId = input.optString("video_id", null)
        val url = input.optString("url", null)
        if (videoId == null && url == null) return@withContext errorResult("Either video_id or url required")
        val downloadUrl = url ?: "https://youtube.com/watch?v=$videoId"
        Log.d(TAG, "⬇ DOWNLOADING: $downloadUrl")
        val media = mediaIntelligence.downloadYouTubeVideo(downloadUrl, audioOnly = true) ?: return@withContext errorResult("Download failed")
        Log.d(TAG, "✓ DOWNLOAD SUCCESS: ${media.title} (id=${media.id})")
        successResult(mapOf("media_id" to media.id, "title" to media.title, "duration_ms" to media.duration, "local_path" to media.localFilePath))
    }
    private suspend fun searchLibrary(input: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val query = input.optString("query", null) ?: return@withContext errorResult("query required")
        val maxResults = input.optInt("max_results", 5)
        val minScore = input.optDouble("min_score", 0.3).toFloat()
        Log.d(TAG, "🔍 SEARCHING LIBRARY: $query (max=$maxResults, minScore=$minScore)")
        val results = semanticSearch.search(query, maxResults, minScore)
        if (results.isEmpty()) return@withContext errorResult("No matching media found in library")
        val resultsArray = JSONArray()
        results.forEach { result -> resultsArray.put(JSONObject().apply { put("media_id", result.media.id); put("title", result.media.title); put("channel", result.media.channelName); put("duration_ms", result.media.duration); put("score", result.score); put("local_path", result.media.localFilePath) }) }
        Log.d(TAG, "✓ LIBRARY SEARCH: Found ${results.size} results")
        successResult(mapOf("query" to query, "total_results" to results.size, "results" to resultsArray))
    }
    private fun formatDuration(ms: Long) = (ms / 1000).let { seconds -> when { seconds < 60 -> "${seconds}s"; seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"; else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m" } }
}
