package com.ambientai.data.entities

import io.objectbox.annotation.Entity
import io.objectbox.annotation.HnswIndex
import io.objectbox.annotation.Id

@Entity
data class TranscriptSegment(
    @Id var id: Long = 0,
    var transcriptId: Long,
    var mediaId: Long = 0,
    var text: String,
    var startMs: Long,
    var endMs: Long,
    var speakerLabel: String? = null,
    var segmentIndex: Int = 0,
    var embeddingModel: String? = null,
    @HnswIndex(dimensions = 100, neighborsPerNode = 30, indexingSearchCount = 100)
    var embedding: FloatArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as TranscriptSegment
        if (id != other.id) return false
        if (transcriptId != other.transcriptId) return false
        if (mediaId != other.mediaId) return false
        if (text != other.text) return false
        if (startMs != other.startMs) return false
        if (endMs != other.endMs) return false
        if (speakerLabel != other.speakerLabel) return false
        if (segmentIndex != other.segmentIndex) return false
        if (embedding != null) {
            if (other.embedding == null) return false
            if (!embedding.contentEquals(other.embedding)) return false
        } else if (other.embedding != null) return false
        return true
    }
    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + transcriptId.hashCode()
        result = 31 * result + mediaId.hashCode()
        result = 31 * result + text.hashCode()
        result = 31 * result + startMs.hashCode()
        result = 31 * result + endMs.hashCode()
        result = 31 * result + (speakerLabel?.hashCode() ?: 0)
        result = 31 * result + segmentIndex.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        return result
    }
}
