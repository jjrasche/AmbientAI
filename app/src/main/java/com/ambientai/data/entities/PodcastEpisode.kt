package com.ambientai.data.entities

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

@Entity
data class PodcastEpisode(
    @Id var id: Long = 0,
    @Index var subscriptionId: Long = 0,
    @Index var guid: String = "",
    var title: String = "",
    var description: String = "",
    var audioUrl: String = "",
    var localFilePath: String? = null,
    var duration: Long = 0,
    var publishDate: Long = 0,
    var episodeNumber: Int = 0,
    var chaptersUrl: String? = null,
    var artworkUrl: String? = null,
    var downloadStatus: String = "none",
    var downloadProgress: Int = 0,
    var transcriptStatus: String = "none",
    var playbackPosition: Long = 0,
    var completed: Boolean = false,
    var createdAt: Long = System.currentTimeMillis()
)
