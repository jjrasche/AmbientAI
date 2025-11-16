package com.ambientai.data.entities

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id

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
    var lastPlayedPosition: Long = 0,
    var createdDate: Long = System.currentTimeMillis()
)
