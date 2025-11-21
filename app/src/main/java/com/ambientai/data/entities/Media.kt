package com.ambientai.data.entities

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.annotation.Index

@Entity
data class Media(
    @Id var id: Long = 0,
    var title: String,
    var sourceType: String,
    var mediaType: String,
    var sourceUrl: String,
    var duration: Long,
    var thumbnailLocalPath: String? = null,
    var channelName: String? = null,
    var publishDate: Long = 0,
    var localFilePath: String? = null,
    var playbackPosition: Long = 0,
    var completed: Boolean = false,
    var downloadStatus: String? = null,
    var createdDate: Long = System.currentTimeMillis()
)
