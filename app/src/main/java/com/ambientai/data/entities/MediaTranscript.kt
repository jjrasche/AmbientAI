package com.ambientai.data.entities

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id

@Entity
data class MediaTranscript(
    @Id var id: Long = 0,
    var mediaId: Long,
    var source: String,
    var fetchedAt: Long = System.currentTimeMillis(),
    var createdDate: Long = System.currentTimeMillis()
)
