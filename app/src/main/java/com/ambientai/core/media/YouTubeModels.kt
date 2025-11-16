package com.ambientai.core.media

data class Caption(
    val text: String,
    val startMs: Long,
    val durationMs: Long
) {
    val endMs: Long get() = startMs + durationMs
}

data class YouTubeChapter(
    val title: String,
    val startMs: Long,
    val endMs: Long
)

data class YouTubeComment(
    val text: String,
    val author: String,
    val likeCount: Int,
    val timestamp: Long
)

data class YouTubeMetadata(
    val id: String,
    val title: String,
    val channelName: String,
    val duration: Long,
    val thumbnailUrl: String,
    val publishDate: Long,
    val chapters: List<YouTubeChapter> = emptyList(),
    val comments: List<YouTubeComment> = emptyList()
)
