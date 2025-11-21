package com.ambientai.data.entities

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

@Entity
data class YouTubeVideo(
    @Id var id: Long = 0,
    @Index var subscriptionId: Long = 0,
    @Index var videoId: String = "",
    var title: String = "",
    var description: String = "",
    var channelName: String = "",
    var thumbnailUrl: String? = null,
    var duration: Long = 0,
    var publishDate: Long = 0,
    var playbackPosition: Long = 0,
    var completed: Boolean = false,
    var downloadStatus: String = "none",
    var localFilePath: String? = null,
    var createdAt: Long = System.currentTimeMillis()
)
