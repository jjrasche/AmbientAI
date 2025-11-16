package com.ambientai.data.repositories

import com.ambientai.data.entities.TranscriptSegment

interface ITranscriptSegmentRepository {
    fun save(segment: TranscriptSegment): TranscriptSegment
    fun saveAll(segments: List<TranscriptSegment>)
    fun getById(id: Long): TranscriptSegment?
    fun getByTranscriptId(transcriptId: Long): List<TranscriptSegment>
    fun getAtPosition(transcriptId: Long, positionMs: Long): TranscriptSegment?
    fun getInWindow(transcriptId: Long, startMs: Long, endMs: Long): List<TranscriptSegment>
    fun semanticSearch(transcriptId: Long, queryEmbedding: FloatArray, topK: Int): List<TranscriptSegment>
    fun semanticSearchAllMedia(queryEmbedding: FloatArray, topK: Int): List<TranscriptSegment>
    fun delete(id: Long)
}
