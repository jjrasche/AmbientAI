package com.ambientai.core.media

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaHandler @Inject constructor(
    private val sources: Set<@JvmSuppressWildcards MediaSource>,
    private val playbackService: MediaPlaybackService
) {
    companion object {
        private const val TAG = "MediaHandler"
    }

    suspend fun execute(actionName: String, input: JSONObject): JSONObject = when(actionName) {
        // Smart default with priority search
        "media.play" -> playWithPriority(input)

        // Explicit source overrides
        "media.play.local" -> playFromSource(input, MediaSourceType.LOCAL_AUDIO)
        "media.play.youtube" -> playFromSource(input, MediaSourceType.YOUTUBE)
        "media.play.podcast" -> playFromSource(input, MediaSourceType.PODCAST_RSS)

        // Search actions (return results, don't play)
        "media.search" -> searchWithPriority(input)
        "media.search.local" -> searchSource(input, MediaSourceType.LOCAL_AUDIO)
        "media.search.youtube" -> searchSource(input, MediaSourceType.YOUTUBE)
        "media.search.all" -> searchAllSources(input)

        // Playback control (source-agnostic)
        "media.pause" -> pause()
        "media.resume" -> resume()
        "media.stop" -> stop()
        "media.next" -> next()
        "media.previous" -> previous()
        "media.getNowPlaying" -> getNowPlaying()

        // Library management
        "media.scan" -> scanLocal()
        "media.download" -> download(input)

        else -> errorResult("Unknown action: $actionName")
    }

    private suspend fun playWithPriority(input: JSONObject): JSONObject {
        val query = input.getString("query")
        val mediaType = input.optString("mediaType", "MUSIC")

        Log.d(TAG, "🎵 Play request: '$query' (type=$mediaType)")

        // Detect source hints in query
        detectSourceHint(query)?.let { forcedSource ->
            Log.d(TAG, "  → Detected source hint: $forcedSource")
            return playFromSource(input, forcedSource)
        }

        // Priority order based on media type
        val priority = when(mediaType.uppercase()) {
            "PODCAST" -> listOf(MediaSourceType.PODCAST_RSS, MediaSourceType.DOWNLOADED)
            else -> listOf(MediaSourceType.LOCAL_AUDIO, MediaSourceType.DOWNLOADED, MediaSourceType.YOUTUBE)
        }

        // Try sources in order, stop at first match
        for (sourceType in priority) {
            val source = getSource(sourceType)
            if (source == null) {
                Log.d(TAG, "  → Source $sourceType not available, skipping")
                continue
            }

            Log.d(TAG, "  → Searching $sourceType...")
            val results = source.search(query)

            if (results.isNotEmpty()) {
                Log.d(TAG, "  ✓ Found ${results.size} results in $sourceType")
                val item = results.first()
                return playItem(item, source)
            }
        }

        Log.w(TAG, "  ✖ No results found in any source")
        return errorResult("No media found for '$query'")
    }

    private suspend fun playFromSource(
        input: JSONObject,
        sourceType: MediaSourceType
    ): JSONObject {
        val query = input.getString("query")
        val source = getSource(sourceType)
            ?: return errorResult("Source not available: $sourceType")

        Log.d(TAG, "🎵 Playing from $sourceType: '$query'")

        val results = source.search(query)
        val item = results.firstOrNull()
            ?: return errorResult("No results from $sourceType for '$query'")

        return playItem(item, source)
    }

    private suspend fun playItem(item: MediaItem, source: MediaSource): JSONObject = withContext(Dispatchers.IO) {
        try {
            val playbackUrl = source.getPlaybackUrl(item)
            playbackService.play(item, playbackUrl)

            successResult(mapOf(
                "title" to item.title,
                "creator" to item.creator,
                "sourceType" to item.sourceType.name,
                "mediaType" to item.mediaType.name
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play ${item.title}", e)
            errorResult("Playback failed: ${e.message}")
        }
    }

    private suspend fun searchWithPriority(input: JSONObject): JSONObject {
        val query = input.getString("query")
        val maxResults = input.optInt("maxResults", 10)

        // Search local first (fast), then online if needed
        val localSource = getSource(MediaSourceType.LOCAL_AUDIO)
        val localResults = localSource?.search(query) ?: emptyList()

        if (localResults.isNotEmpty()) {
            return successResult(mapOf(
                "results" to JSONArray(localResults.map { it.toJson() }),
                "source" to "LOCAL_AUDIO",
                "count" to localResults.size
            ))
        }

        // Fallback to YouTube
        val youtubeSource = getSource(MediaSourceType.YOUTUBE)
        val youtubeResults = youtubeSource?.search(query) ?: emptyList()

        return successResult(mapOf(
            "results" to JSONArray(youtubeResults.take(maxResults).map { it.toJson() }),
            "source" to "YOUTUBE",
            "count" to youtubeResults.size
        ))
    }

    private suspend fun searchSource(
        input: JSONObject,
        sourceType: MediaSourceType
    ): JSONObject {
        val query = input.getString("query")
        val maxResults = input.optInt("maxResults", 10)
        val source = getSource(sourceType)
            ?: return errorResult("Source not available: $sourceType")

        val results = source.search(query)

        return successResult(mapOf(
            "results" to JSONArray(results.take(maxResults).map { it.toJson() }),
            "source" to sourceType.name,
            "count" to results.size
        ))
    }

    private suspend fun searchAllSources(input: JSONObject): JSONObject {
        val query = input.getString("query")
        val maxResults = input.optInt("maxResults", 10)

        val allResults = sources.flatMap { source ->
            try {
                source.search(query)
            } catch (e: Exception) {
                Log.w(TAG, "Source search failed: ${e.message}")
                emptyList()
            }
        }

        return successResult(mapOf(
            "results" to JSONArray(allResults.take(maxResults).map { it.toJson() }),
            "sources" to "ALL",
            "count" to allResults.size
        ))
    }

    private fun pause(): JSONObject {
        playbackService.pause()
        return successResult(mapOf("message" to "Paused"))
    }

    private fun resume(): JSONObject {
        playbackService.resume()
        return successResult(mapOf("message" to "Resumed"))
    }

    private fun stop(): JSONObject {
        playbackService.stop()
        return successResult(mapOf("message" to "Stopped"))
    }

    private suspend fun next(): JSONObject {
        playbackService.next()
        val item = playbackService.getPlaylist().getOrNull(playbackService.getCurrentIndex())
            ?: return errorResult("No next track")

        val source = sources.firstOrNull { it.canHandle(item) }
            ?: return errorResult("No source can handle next track")

        return playItem(item, source)
    }

    private suspend fun previous(): JSONObject {
        playbackService.previous()
        val item = playbackService.getPlaylist().getOrNull(playbackService.getCurrentIndex())
            ?: return errorResult("No previous track")

        val source = sources.firstOrNull { it.canHandle(item) }
            ?: return errorResult("No source can handle previous track")

        return playItem(item, source)
    }

    private fun getNowPlaying(): JSONObject {
        val item = playbackService.getCurrentItem()
        return item?.let {
            successResult(mapOf(
                "title" to it.title,
                "creator" to it.creator,
                "sourceType" to it.sourceType.name,
                "mediaType" to it.mediaType.name,
                "isPlaying" to playbackService.isPlaying(),
                "position" to playbackService.getCurrentPosition(),
                "duration" to playbackService.getDuration(),
                "response" to "${it.title} by ${it.creator}"
            ))
        } ?: errorResult("Nothing is playing")
    }

    private suspend fun scanLocal(): JSONObject {
        val localSource = getSource(MediaSourceType.LOCAL_AUDIO) as? LocalAudioSource
            ?: return errorResult("Local audio source not available")

        val songs = localSource.getSongs()
        return successResult(mapOf(
            "songsFound" to songs.size,
            "message" to "Scanned ${songs.size} local audio files"
        ))
    }

    private suspend fun download(input: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val query = input.getString("query")

        // Search YouTube
        val youtubeSource = getSource(MediaSourceType.YOUTUBE)
            ?: return@withContext errorResult("YouTube source not available")

        val results = youtubeSource.search(query)
        val item = results.firstOrNull()
            ?: return@withContext errorResult("No results found for '$query'")

        try {
            val localPath = youtubeSource.download(item)
            successResult(mapOf(
                "title" to item.title,
                "localPath" to localPath,
                "message" to "Downloaded ${item.title}"
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            errorResult("Download failed: ${e.message}")
        }
    }

    private fun detectSourceHint(query: String): MediaSourceType? = when {
        query.contains("youtube", ignoreCase = true) -> MediaSourceType.YOUTUBE
        query.contains("podcast", ignoreCase = true) -> MediaSourceType.PODCAST_RSS
        query.contains("online", ignoreCase = true) -> MediaSourceType.YOUTUBE
        else -> null
    }

    private fun getSource(type: MediaSourceType) = sources.firstOrNull {
        it.canHandle(MediaItem(
            title = "",
            creator = "",
            sourceType = type,
            mediaType = MediaType.MUSIC,
            playbackUrl = ""
        ))
    }

    private fun successResult(data: Map<String, Any?> = emptyMap()) =
        JSONObject().apply {
            put("success", true)
            data.forEach { (k, v) -> put(k, v) }
        }

    private fun errorResult(message: String) =
        JSONObject().apply {
            put("success", false)
            put("error", message)
        }
}
