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
class PodcastWorkflowHandler @Inject constructor(
    private val subscriptionManager: PodcastSubscriptionManager,
    private val podcastIndexService: PodcastIndexService,
    private val mediaLibrary: MediaLibrary
) {
    companion object {
        private const val TAG = "PodcastWorkflowHandler"
    }

    suspend fun execute(actionName: String, input: JSONObject) = when (actionName) {
        "podcast.subscribe" -> subscribe(input)
        "podcast.unsubscribe" -> unsubscribe(input)
        "podcast.refresh" -> refresh(input)
        "podcast.listSubscriptions" -> listSubscriptions(input)
        "podcast.listEpisodes" -> listEpisodes(input)
        "podcast.search" -> search(input)
        "podcast.searchShows" -> searchShows(input)
        "podcast.getEpisode" -> getEpisode(input)
        else -> errorResult("Unknown podcast action: $actionName")
    }

    private fun successResult(data: Map<String, Any?> = emptyMap()) = JSONObject().apply { put("success", true); data.forEach { (k, v) -> put(k, v) } }
    private fun errorResult(message: String) = JSONObject().apply { put("success", false); put("error", message) }

    private suspend fun subscribe(input: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val feedUrl = input.optString("feed_url", null) ?: return@withContext errorResult("feed_url required")
        Log.d(TAG, "Subscribing to: $feedUrl")
        val subscription = subscriptionManager.subscribe(feedUrl) ?: return@withContext errorResult("Failed to subscribe")
        Log.d(TAG, "Subscribed: ${subscription.title}")
        successResult(mapOf(
            "subscription_id" to subscription.id,
            "title" to subscription.title,
            "author" to subscription.author,
            "feed_url" to subscription.feedUrl
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
        Log.d(TAG, "Refreshing subscriptions")
        val newCount = subscriptionManager.refresh()
        Log.d(TAG, "Found $newCount new episodes")
        successResult(mapOf("new_episodes_count" to newCount))
    }

    private suspend fun listSubscriptions(input: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        Log.d(TAG, "Listing subscriptions")
        val subscriptions = subscriptionManager.getSubscriptions().first()
        val subsArray = JSONArray()
        subscriptions.forEach { sub ->
            subsArray.put(JSONObject().apply {
                put("subscription_id", sub.id)
                put("title", sub.title)
                put("author", sub.author)
                put("feed_url", sub.feedUrl)
            })
        }
        successResult(mapOf("count" to subscriptions.size, "subscriptions" to subsArray))
    }

    private suspend fun listEpisodes(input: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val subscriptionId = input.optLong("subscription_id", 0)
        val limit = input.optInt("limit", 20)
        Log.d(TAG, "Listing episodes: subscription=$subscriptionId, limit=$limit")
        val episodes = if (subscriptionId > 0) {
            mediaLibrary.getBySubscription(subscriptionId, Platform.PODCAST).first()
        } else {
            mediaLibrary.getUnplayed().first().filter { it.sourceType == MediaSourceType.PODCAST_RSS }
        }
        val episodesArray = JSONArray()
        episodes.take(limit).forEach { episode ->
            episodesArray.put(JSONObject().apply {
                put("episode_id", episode.id)
                put("title", episode.title)
                put("duration_ms", episode.duration)
                put("publish_date", episode.metadata.publishDate)
            })
        }
        successResult(mapOf("count" to episodes.size, "episodes" to episodesArray))
    }

    private suspend fun search(input: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val query = input.optString("query", null) ?: return@withContext errorResult("query required")
        val maxResults = input.optInt("max_results", 10)
        Log.d(TAG, "Searching library episodes: $query")
        val results = mediaLibrary.search(query).first().filter { it.sourceType == MediaSourceType.PODCAST_RSS }
        val resultsArray = JSONArray()
        results.take(maxResults).forEach { result ->
            resultsArray.put(JSONObject().apply {
                put("episode_id", result.id)
                put("title", result.title)
                put("show", result.creator)
                put("description", result.metadata.episodeDescription)
            })
        }
        Log.d(TAG, "Found ${results.size} results")
        successResult(mapOf("query" to query, "total_results" to results.size, "results" to resultsArray))
    }

    private suspend fun searchShows(input: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val query = input.optString("query", null) ?: return@withContext errorResult("query required")
        val maxResults = input.optInt("max_results", 10)
        Log.d(TAG, "Searching podcast shows: $query")
        val results = podcastIndexService.search(query)
        val resultsArray = JSONArray()
        results.take(maxResults).forEach { result ->
            resultsArray.put(JSONObject().apply {
                put("title", result.title)
                put("author", result.author)
                put("feed_url", result.feedUrl)
                put("description", result.description)
            })
        }
        Log.d(TAG, "Found ${results.size} shows")
        successResult(mapOf("query" to query, "total_results" to results.size, "results" to resultsArray))
    }

    private suspend fun getEpisode(input: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val episodeId = input.optLong("episode_id", 0)
        if (episodeId <= 0) return@withContext errorResult("episode_id required")
        val episode = mediaLibrary.getById(episodeId, Platform.PODCAST) ?: return@withContext errorResult("Episode not found")
        successResult(mapOf(
            "episode_id" to episode.id,
            "title" to episode.title,
            "show" to episode.creator,
            "duration_ms" to episode.duration,
            "playback_url" to episode.playbackUrl
        ))
    }
}
