package com.ambientai.data.repositories

import com.ambientai.data.entities.TranscriptSegment
import com.ambientai.data.entities.TranscriptSegment_
import io.objectbox.Box
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import io.objectbox.query.OrderFlags
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TranscriptSegmentRepository @Inject constructor(
    private val boxStore: BoxStore
) : ITranscriptSegmentRepository {
    private val box: Box<TranscriptSegment> = boxStore.boxFor()
    override fun save(segment: TranscriptSegment) = segment.also { box.put(it) }
    override fun saveAll(segments: List<TranscriptSegment>) { box.put(segments) }
    override fun getById(id: Long) = box.get(id)
    override fun getByTranscriptId(transcriptId: Long) = box.query().equal(TranscriptSegment_.transcriptId, transcriptId).order(TranscriptSegment_.startMs).build().find()
    override fun getAtPosition(transcriptId: Long, positionMs: Long) = box.query().equal(TranscriptSegment_.transcriptId, transcriptId).lessOrEqual(TranscriptSegment_.startMs, positionMs).greaterOrEqual(TranscriptSegment_.endMs, positionMs).build().findFirst()
    override fun getInWindow(transcriptId: Long, startMs: Long, endMs: Long) = box.query().equal(TranscriptSegment_.transcriptId, transcriptId).between(TranscriptSegment_.startMs, startMs, endMs).order(TranscriptSegment_.startMs).build().find()
    override fun semanticSearch(transcriptId: Long, queryEmbedding: FloatArray, topK: Int) = box.query(TranscriptSegment_.transcriptId.equal(transcriptId)).nearestNeighbors(TranscriptSegment_.embedding, queryEmbedding, topK).build().find()
    override fun semanticSearchAllMedia(queryEmbedding: FloatArray, topK: Int) = box.query().nearestNeighbors(TranscriptSegment_.embedding, queryEmbedding, topK).build().find()
    override fun delete(id: Long) { box.remove(id) }
}
