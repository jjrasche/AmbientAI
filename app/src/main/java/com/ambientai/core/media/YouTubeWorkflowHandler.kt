package com.ambientai.core.media

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeWorkflowHandler @Inject constructor(
    private val subscriptionManager: YouTubeSubscriptionManager,
    private val channelService: YouTubeChannelService,
    private val mediaLibrary: MediaLibrary
) {
    companion object {
        private const val TAG = "YouTubeWorkflowHandler"
    }

    suspend fun execute(actionName: String, input: JSONObject) = when (actionName) {
        "youtube.subscribe" -> subscribe(input)
        "youtube.unsubscribe" -> unsubscribe(input)
        "youtube.refresh" -> refresh(input)
        "youtube.listSubscriptions" -> listSubscriptions(input)
        "youtube.listVideos" -> listVideos(input)
        "youtube.search" -> search(input)
        "youtube.searchChannels" -> searchChannels(input)
        "youtube.getVideo" -> getVideo(input)
        else -> errorResult("Unknown youtube action: $actionName")
    }

    private fun successResult(data: Map<String, Any?> = emptyMap()) = JSONObject().apply { put("success", true); data.forEach { (k, v) -> put(k, v) } }
    private fun errorResult(message: String) = JSONObject().apply { put("success", false); put("error", message) }

    private suspend fun subscribe(input: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val channelUrl = input.optString("channel_url", null) ?: input.optString("channel_id", null) ?: return@withContext errorResult("channel_url or channel_id required")
        Log.d(TAG, "Subscribing to: $channelUrl")
        val subscription = subscriptionManager.subscribe(channelUrl) ?: return@withContext errorResult("Failed to subscribe")
        Log.d(TAG, "Subscribed: ${subscription.channelName}")
        successResult(mapOf(
            "subscription_id" to subscription.id,
            "channel_id" to subscription.channelId,
            "channel_name" to subscription.channelName
        ))
    }

    private suspend fun unsubscribe(input: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val subscriptionId = input.optLong("subscription_id", 0)
        if (subscriptionId <= 0) return@withContext errorResult("subscription_id required")
        Log.d(TAG, "Unsubscribing: $subscriptionId")
        subscriptionManager.unsubscribe(subscriptionId)
        successResult(mapOf("unsubscribed" to subscriptionId))
    }

    private suspend fun refresh(input: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        Log.d(TAG, "Refreshing YouTube subscriptions")
        val newCount = subscriptionManager.refresh()
        Log.d(TAG, "Found $newCount new videos")
        successResult(mapOf("new_videos_count" to newCount))
    }

    private suspend fun listSubscriptions(input: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        Log.d(TAG, "Listing YouTube subscriptions")
        val subscriptions = subscriptionManager.getSubscriptions().first()
        val subsArray = JSONArray()
        subscriptions.forEach { sub ->
            subsArray.put(JSONObject().apply {
                put("subscription_id", sub.id)
                put("channel_id", sub.channelId)
                put("channel_name", sub.channelName)
            })
        }
        successResult(mapOf("count" to subscriptions.size, "subscriptions" to subsArray))
    }

    private suspend fun listVideos(input: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val subscriptionId = input.optLong("subscription_id", 0)
        val limit = input.optInt("limit", 20)
        Log.d(TAG, "Listing videos: subscription=$subscriptionId, limit=$limit")
        val videos = if (subscriptionId > 0) {
            mediaLibrary.getBySubscription(subscriptionId, Platform.YOUTUBE).first()
        } else {
            mediaLibrary.getUnplayed().first().filter { it.sourceType == MediaSourceType.YOUTUBE }
        }
        val videosArray = JSONArray()
        videos.take(limit).forEach { video ->
            videosArray.put(JSONObject().apply {
                put("video_id", video.id)
                put("title", video.title)
                put("channel", video.creator)
                put("duration_ms", video.duration)
                put("publish_date", video.metadata.publishDate)
            })
        }
        successResult(mapOf("count" to videos.size, "videos" to videosArray))
    }

    private suspend fun search(input: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val query = input.optString("query", null) ?: return@withContext errorResult("query required")
        val maxResults = input.optInt("max_results", 10)
        Log.d(TAG, "Searching library videos: $query")
        val results = mediaLibrary.search(query).first().filter { it.sourceType == MediaSourceType.YOUTUBE }
        val resultsArray = JSONArray()
        results.take(maxResults).forEach { result ->
            resultsArray.put(JSONObject().apply {
                put("video_id", result.id)
                put("title", result.title)
                put("channel", result.creator)
                put("description", result.metadata.episodeDescription)
            })
        }
        Log.d(TAG, "Found ${results.size} results")
        successResult(mapOf("query" to query, "total_results" to results.size, "results" to resultsArray))
    }

    private suspend fun searchChannels(input: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val query = input.optString("query", null) ?: return@withContext errorResult("query required")
        val maxResults = input.optInt("max_results", 10)
        Log.d(TAG, "Searching YouTube channels: $query")
        val results = channelService.searchChannels(query, maxResults)
        val resultsArray = JSONArray()
        results.forEach { channel ->
            resultsArray.put(JSONObject().apply {
                put("channel_id", channel.channelId)
                put("channel_name", channel.channelName)
                put("thumbnail_url", channel.thumbnailUrl)
            })
        }
        Log.d(TAG, "Found ${results.size} channels")
        successResult(mapOf("query" to query, "total_results" to results.size, "results" to resultsArray))
    }

    private suspend fun getVideo(input: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val videoId = input.optLong("video_id", 0)
        if (videoId <= 0) return@withContext errorResult("video_id required")
        val video = mediaLibrary.getById(videoId, Platform.YOUTUBE) ?: return@withContext errorResult("Video not found")
        successResult(mapOf(
            "video_id" to video.id,
            "title" to video.title,
            "channel" to video.creator,
            "duration_ms" to video.duration,
            "playback_url" to video.playbackUrl
        ))
    }
}
