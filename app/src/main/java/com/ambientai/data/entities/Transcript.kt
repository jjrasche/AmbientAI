package com.ambientai.data.entities

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id

/**
 * Entity representing a voice transcript with associated audio file.
 * Stored in ObjectBox for Phase 1 of the voice pipeline.
 */
@Entity
data class Transcript(
    @Id var id: Long = 0,
    var text: String,
    var audioFilePath: String,
    var timestamp: Long,
    var excludeFromContext: Boolean = false
)