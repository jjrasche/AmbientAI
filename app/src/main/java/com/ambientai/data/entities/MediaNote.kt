package com.ambientai.data.entities

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id

@Entity
data class MediaNote(
    @Id var id: Long = 0,
    var segmentId: Long,
    var noteText: String,
    var createdDate: Long = System.currentTimeMillis()
)
