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
class SubscriptionWorkflowHandler @Inject constructor(
    private val podcastManager: PodcastSubscriptionManager,
    private val youtubeManager: YouTubeSubscriptionManager,
    private val podcastIndexService: PodcastIndexService,
    private val youtubeChannelService: YouTubeChannelService,
    private val mediaLibrary: MediaLibrary
) {
    companion object {
        private const val TAG = "SubscriptionHandler"
    }

    suspend fun execute(actionName: String, input: JSONObject) = when (actionName) {
        "subscription.subscribe" -> subscribe(input)
        "subscription.unsubscribe" -> unsubscribe(input)
        "subscription.refresh" -> refresh(input)
        "subscription.list" -> listSubscriptions(input)
        "subscription.listContent" -> listContent(input)
        "subscription.searchPlatform" -> searchPlatform(input)
        "subscription.getContent" -> getContent(input)
        else -> errorResult("Unknown subscription action: $actionName")
    }

    private fun successResult(data: Map<String, Any?> = emptyMap()) = JSONObject().apply { put("success", true); data.forEach { (k, v) -> put(k, v) } }
    private fun errorResult(message: String) = JSONObject().apply { put("success", false); put("error", message) }

    private fun getManager(platform: Platform) = when (platform) {
        Platform.PODCAST -> podcastManager
        Platform.YOUTUBE -> youtubeManager
        else -> null
    }

    private fun parsePlatform(input: JSONObject): Platform? = runCatching {
        Platform.valueOf(input.getString("platform").uppercase())
    }.getOrNull()

    private suspend fun subscribe(input: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val platform = parsePlatform(input) ?: return@withContext errorResult("platform required")
        val identifier = input.optString("identifier", null) ?: return@withContext errorResult("identifier required")
        val manager = getManager(platform) ?: return@withContext errorResult("Unknown platform: $platform")

        Log.d(TAG, "Subscribing to $platform: $identifier")
        val subscription = manager.subscribe(identifier) ?: return@withContext errorResult("Failed to subscribe")

        successResult(mapOf(
            "subscription_id" to subscription.id,
            "title" to subscription.title,
            "platform" to platform.name
        ))
    }

    private suspend fun unsubscribe(input: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val platform = parsePlatform(input) ?: return@withContext errorResult("platform required")
        val subscriptionId = input.optLong("subscription_id", 0)
        if (subscriptionId <= 0) return@withContext errorResult("subscription_id required")

        val manager = getManager(platform) ?: return@withContext errorResult("Unknown platform: $platform")
        manager.unsubscribe(subscriptionId)
        successResult(mapOf("unsubscribed" to subscriptionId))
    }

    private suspend fun refresh(input: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val platformStr = input.optString("platform", "all")

        if (platformStr.equals("all", ignoreCase = true)) {
            val podcastCount = podcastManager.refresh()
            val youtubeCount = youtubeManager.refresh()
            return@withContext successResult(mapOf(
                "new_podcast_count" to podcastCount,
                "new_youtube_count" to youtubeCount,
                "total_new" to (podcastCount + youtubeCount)
            ))
        }

        val platform = parsePlatform(input) ?: return@withContext errorResult("Invalid platform")
        val manager = getManager(platform) ?: return@withContext errorResult("Unknown platform: $platform")
        val newCount = manager.refresh()
        successResult(mapOf("new_count" to newCount, "platform" to platform.name))
    }

    private suspend fun listSubscriptions(input: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val platformStr = input.optString("platform", "all")
        val subsArray = JSONArray()

        if (platformStr.equals("all", ignoreCase = true)) {
            podcastManager.getSubscriptions().first().forEach { sub ->
                subsArray.put(JSONObject().apply {
                    put("subscription_id", sub.id)
                    put("title", sub.title)
                    put("platform", Platform.PODCAST.name)
                })
            }
            youtubeManager.getSubscriptions().first().forEach { sub ->
                subsArray.put(JSONObject().apply {
                    put("subscription_id", sub.id)
                    put("title", sub.title)
                    put("platform", Platform.YOUTUBE.name)
                })
            }
        } else {
            val platform = parsePlatform(input) ?: return@withContext errorResult("Invalid platform")
            val manager = getManager(platform) ?: return@withContext errorResult("Unknown platform")
            manager.getSubscriptions().first().forEach { sub ->
                subsArray.put(JSONObject().apply {
                    put("subscription_id", sub.id)
                    put("title", sub.title)
                    put("platform", platform.name)
                })
            }
        }

        successResult(mapOf("count" to subsArray.length(), "subscriptions" to subsArray))
    }

    private suspend fun listContent(input: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val platform = parsePlatform(input) ?: return@withContext errorResult("platform required")
        val subscriptionId = input.optLong("subscription_id", 0)
        val limit = input.optInt("limit", 20)

        val items = if (subscriptionId > 0) {
            mediaLibrary.getBySubscription(subscriptionId, platform).first()
        } else {
            mediaLibrary.getUnplayed().first().filter {
                when (platform) {
                    Platform.PODCAST -> it.sourceType == MediaSourceType.PODCAST_RSS
                    Platform.YOUTUBE -> it.sourceType == MediaSourceType.YOUTUBE
                    else -> false
                }
            }
        }

        val itemsArray = JSONArray()
        items.take(limit).forEach { item ->
            itemsArray.put(JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("creator", item.creator)
                put("duration_ms", item.duration)
                put("publish_date", item.metadata.publishDate)
            })
        }

        successResult(mapOf("count" to items.size, "items" to itemsArray))
    }

    private suspend fun searchPlatform(input: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val platform = parsePlatform(input) ?: return@withContext errorResult("platform required")
        val query = input.optString("query", null) ?: return@withContext errorResult("query required")
        val maxResults = input.optInt("max_results", 10)

        val resultsArray = JSONArray()

        when (platform) {
            Platform.PODCAST -> {
                podcastIndexService.search(query).take(maxResults).forEach { show ->
                    resultsArray.put(JSONObject().apply {
                        put("identifier", show.feedUrl)
                        put("title", show.title)
                        put("author", show.author)
                        put("description", show.description)
                    })
                }
            }
            Platform.YOUTUBE -> {
                youtubeChannelService.searchChannels(query, maxResults).forEach { channel ->
                    resultsArray.put(JSONObject().apply {
                        put("identifier", channel.channelId)
                        put("title", channel.channelName)
                        put("thumbnail_url", channel.thumbnailUrl)
                    })
                }
            }
            else -> return@withContext errorResult("Platform search not supported: $platform")
        }

        successResult(mapOf("query" to query, "count" to resultsArray.length(), "results" to resultsArray))
    }

    private suspend fun getContent(input: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val platform = parsePlatform(input) ?: return@withContext errorResult("platform required")
        val contentId = input.optLong("id", 0)
        if (contentId <= 0) return@withContext errorResult("id required")

        val item = mediaLibrary.getById(contentId, platform) ?: return@withContext errorResult("Content not found")

        successResult(mapOf(
            "id" to item.id,
            "title" to item.title,
            "creator" to item.creator,
            "duration_ms" to item.duration,
            "playback_url" to item.playbackUrl,
            "platform" to platform.name
        ))
    }
}
