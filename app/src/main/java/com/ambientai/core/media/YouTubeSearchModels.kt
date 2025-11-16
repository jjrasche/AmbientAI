package com.ambientai.core.media

data class YouTubeSearchResult(
    val videoId: String,
    val title: String,
    val channelName: String,
    val thumbnailUrl: String,
    val duration: Long,
    val viewCount: Long,
    val publishedDate: Long,
    val description: String
)

data class YouTubeSearchResponse(
    val results: List<YouTubeSearchResult>,
    val query: String,
    val totalResults: Int
)
