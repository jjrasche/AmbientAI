package com.ambientai.data.entities

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id

@Entity
data class MediaHistory(
    @Id var id: Long = 0,
    var mediaPath: String,
    var mediaType: String,
    var timestamp: Long,
    var durationPlayedMs: Long
)
