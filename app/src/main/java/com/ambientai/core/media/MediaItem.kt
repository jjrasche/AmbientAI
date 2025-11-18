package com.ambientai.core.media

import org.json.JSONObject

data class MediaItem(
    val id: Long = 0,
    val title: String,
    val creator: String,
    val sourceType: MediaSourceType,
    val mediaType: MediaType,
    val playbackUrl: String,
    val duration: Long = 0,
    val metadata: MediaMetadata = MediaMetadata()
) {
    fun toJson() = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("creator", creator)
        put("sourceType", sourceType.name)
        put("mediaType", mediaType.name)
        put("playbackUrl", playbackUrl)
        put("duration", duration)
        put("metadata", metadata.toJson())
    }

    companion object {
        fun fromJson(json: JSONObject) = MediaItem(
            id = json.optLong("id", 0),
            title = json.getString("title"),
            creator = json.getString("creator"),
            sourceType = MediaSourceType.valueOf(json.getString("sourceType")),
            mediaType = MediaType.valueOf(json.getString("mediaType")),
            playbackUrl = json.getString("playbackUrl"),
            duration = json.optLong("duration", 0),
            metadata = json.optJSONObject("metadata")?.let { MediaMetadata.fromJson(it) } ?: MediaMetadata()
        )
    }
}

enum class MediaSourceType {
    LOCAL_AUDIO,
    YOUTUBE,
    PODCAST_RSS,
    DOWNLOADED
}

enum class MediaType {
    MUSIC,
    PODCAST,
    AUDIOBOOK,
    VIDEO
}

data class MediaMetadata(
    val album: String? = null,
    val trackNumber: Int? = null,
    val channelName: String? = null,
    val episodeNumber: Int? = null,
    val podcastName: String? = null,
    val thumbnailPath: String? = null,
    val publishDate: Long? = null
) {
    fun toJson() = JSONObject().apply {
        album?.let { put("album", it) }
        trackNumber?.let { put("trackNumber", it) }
        channelName?.let { put("channelName", it) }
        episodeNumber?.let { put("episodeNumber", it) }
        podcastName?.let { put("podcastName", it) }
        thumbnailPath?.let { put("thumbnailPath", it) }
        publishDate?.let { put("publishDate", it) }
    }

    companion object {
        fun fromJson(json: JSONObject) = MediaMetadata(
            album = json.optString("album", null),
            trackNumber = json.optInt("trackNumber", -1).takeIf { it >= 0 },
            channelName = json.optString("channelName", null),
            episodeNumber = json.optInt("episodeNumber", -1).takeIf { it >= 0 },
            podcastName = json.optString("podcastName", null),
            thumbnailPath = json.optString("thumbnailPath", null),
            publishDate = json.optLong("publishDate", -1).takeIf { it >= 0 }
        )
    }
}
