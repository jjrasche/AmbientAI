package com.ambientai.data.entities

import io.objectbox.annotation.Entity
import io.objectbox.annotation.Id
import io.objectbox.relation.ToOne

/**
 * Generic log entry that can represent any type of logged data.
 * Uses flexible JSON storage with type classification.
 * Links back to original transcript for reprocessing.
 */
@Entity
data class LogEntry(
    @Id var id: Long = 0,
    var type: String = "",              // "medication", "food", "supplement", etc.
    var data: String = "",              // JSON blob - schema varies by type
    var timestamp: Long = 0
) {
    // ObjectBox manages this relation
    lateinit var transcript: ToOne<Transcript>
}