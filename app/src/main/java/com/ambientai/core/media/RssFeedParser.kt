package com.ambientai.core.media

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RssFeedParser @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "RssFeedParser"
        private val DATE_FORMATS = listOf(
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US),
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US),
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        )
    }

    suspend fun parseFeed(feedUrl: String): ParsedFeed? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(feedUrl).build()
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Failed to fetch feed: ${response.code}")
                return@withContext null
            }
            val xml = response.body?.string() ?: return@withContext null
            parseXml(xml, feedUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Parse feed failed: ${e.message}", e)
            null
        }
    }

    private fun parseXml(xml: String, feedUrl: String): ParsedFeed {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(StringReader(xml))

        var showTitle = ""
        var showAuthor = ""
        var showDescription = ""
        var showArtwork: String? = null
        var showWebsite: String? = null
        val episodes = mutableListOf<ParsedEpisode>()

        var inChannel = false
        var inItem = false
        var currentTag = ""
        var itemTitle = ""
        var itemDescription = ""
        var itemGuid = ""
        var itemAudioUrl = ""
        var itemDuration = 0L
        var itemPublishDate = 0L
        var itemEpisodeNumber = 0
        var itemChaptersUrl: String? = null

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name
                    when {
                        currentTag == "channel" -> inChannel = true
                        currentTag == "item" -> {
                            inItem = true
                            itemTitle = ""
                            itemDescription = ""
                            itemGuid = ""
                            itemAudioUrl = ""
                            itemDuration = 0L
                            itemPublishDate = 0L
                            itemEpisodeNumber = 0
                            itemChaptersUrl = null
                        }
                        currentTag == "enclosure" && inItem -> {
                            itemAudioUrl = parser.getAttributeValue(null, "url") ?: ""
                        }
                        currentTag == "image" && inChannel && !inItem -> {
                            showArtwork = parser.getAttributeValue(null, "href")
                        }
                        currentTag == "chapters" && inItem -> {
                            itemChaptersUrl = parser.getAttributeValue(null, "url")
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim() ?: ""
                    if (text.isNotEmpty()) {
                        when {
                            inItem -> when (currentTag) {
                                "title" -> itemTitle = text
                                "description", "summary" -> if (itemDescription.isEmpty()) itemDescription = text
                                "guid" -> itemGuid = text
                                "pubDate" -> itemPublishDate = parseDate(text)
                                "duration" -> itemDuration = parseDuration(text)
                                "episode" -> itemEpisodeNumber = text.toIntOrNull() ?: 0
                            }
                            inChannel -> when (currentTag) {
                                "title" -> showTitle = text
                                "author" -> showAuthor = text
                                "description", "summary" -> if (showDescription.isEmpty()) showDescription = text
                                "link" -> showWebsite = text
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "item" -> {
                            if (itemAudioUrl.isNotEmpty()) {
                                episodes.add(ParsedEpisode(
                                    title = itemTitle,
                                    description = itemDescription,
                                    guid = itemGuid.ifEmpty { itemAudioUrl },
                                    audioUrl = itemAudioUrl,
                                    durationMs = itemDuration,
                                    publishDate = itemPublishDate,
                                    episodeNumber = itemEpisodeNumber,
                                    chaptersUrl = itemChaptersUrl
                                ))
                            }
                            inItem = false
                        }
                        "channel" -> inChannel = false
                    }
                    currentTag = ""
                }
            }
            parser.next()
        }

        return ParsedFeed(
            show = ParsedShow(
                title = showTitle,
                author = showAuthor,
                description = showDescription,
                artworkUrl = showArtwork,
                websiteUrl = showWebsite,
                feedUrl = feedUrl
            ),
            episodes = episodes
        )
    }

    private fun parseDate(dateStr: String): Long {
        for (format in DATE_FORMATS) {
            try {
                return format.parse(dateStr)?.time ?: 0L
            } catch (_: Exception) { }
        }
        return 0L
    }

    private fun parseDuration(durationStr: String): Long {
        return when {
            durationStr.contains(":") -> {
                val parts = durationStr.split(":").map { it.toIntOrNull() ?: 0 }
                when (parts.size) {
                    3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000L
                    2 -> (parts[0] * 60 + parts[1]) * 1000L
                    else -> 0L
                }
            }
            else -> (durationStr.toLongOrNull() ?: 0L) * 1000L
        }
    }
}

data class ParsedFeed(
    val show: ParsedShow,
    val episodes: List<ParsedEpisode>
)

data class ParsedShow(
    val title: String,
    val author: String,
    val description: String,
    val artworkUrl: String?,
    val websiteUrl: String?,
    val feedUrl: String
)

data class ParsedEpisode(
    val title: String,
    val description: String,
    val guid: String,
    val audioUrl: String,
    val durationMs: Long,
    val publishDate: Long,
    val episodeNumber: Int,
    val chaptersUrl: String?
)
