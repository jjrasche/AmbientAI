package com.ambientai.data.entities

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id

@Entity
data class MediaPlaybackSession(
    @Id var id: Long = 0,
    var mediaId: Long,
    var startPositionMs: Long,
    var startTime: Long,
    var questionsAsked: Int = 0
)
