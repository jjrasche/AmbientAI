package com.ambientai.core.enrichment

import android.util.Log
import com.ambientai.data.repositories.IMediaTranscriptRepository
import com.ambientai.data.repositories.ITranscriptSegmentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EnrichmentCleanupService @Inject constructor(
    private val transcriptRepo: IMediaTranscriptRepository,
    private val segmentRepo: ITranscriptSegmentRepository
) {
    companion object { private const val TAG = "EnrichmentCleanup" }
    data class CleanupStats(val transcriptsDeleted: Int, val segmentsDeleted: Int, val goodSegments: Int)
    suspend fun cleanupBadEnrichment(): CleanupStats = withContext(Dispatchers.IO) {
        Log.d(TAG, "🧹 Starting cleanup of bad enrichment data")
        val allTranscripts = transcriptRepo.getAll().filter { it.source == "lyrics" }
        var transcriptsDeleted = 0
        var segmentsDeleted = 0
        var goodSegments = 0
        allTranscripts.forEach { transcript ->
            val segments = segmentRepo.getByTranscriptId(transcript.id)
            val badSegments = segments.filter { it.embedding == null || (it.embedding?.size != 100 && it.embeddingModel != com.ambientai.core.media.TextEmbedder.CURRENT_MODEL) }
            val hasGoodEmbeddings = segments.any { it.embedding != null && it.embedding?.size == 100 }
            if (badSegments.isNotEmpty() && !hasGoodEmbeddings) {
                Log.d(TAG, "  ✖ Deleting transcript ${transcript.id} (${segments.size} segments, all bad/old model)")
                segments.forEach { segmentRepo.delete(it.id); segmentsDeleted++ }
                transcriptRepo.delete(transcript.id)
                transcriptsDeleted++
            } else if (badSegments.isNotEmpty()) {
                Log.d(TAG, "  ⚠ Mixed transcript ${transcript.id} (${badSegments.size}/${segments.size} bad) - keeping good segments")
                badSegments.forEach { segmentRepo.delete(it.id); segmentsDeleted++ }
                goodSegments += (segments.size - badSegments.size)
            } else {
                goodSegments += segments.size
            }
        }
        Log.d(TAG, "✅ Cleanup complete: ${transcriptsDeleted} transcripts deleted, ${segmentsDeleted} bad segments removed, ${goodSegments} good segments kept")
        CleanupStats(transcriptsDeleted, segmentsDeleted, goodSegments)
    }
    suspend fun fixMediaIds(): Int = withContext(Dispatchers.IO) {
        Log.d(TAG, "🔧 Fixing mediaId=0 segments...")
        val allTranscripts = transcriptRepo.getAll().filter { it.source == "lyrics" }
        var fixed = 0
        allTranscripts.forEach { transcript ->
            if (transcript.mediaId != 0L) {
                val segments = segmentRepo.getByTranscriptId(transcript.id)
                segments.filter { it.mediaId == 0L }.forEach { segment ->
                    segment.mediaId = transcript.mediaId
                    segmentRepo.save(segment)
                    fixed++
                }
            }
        }
        Log.d(TAG, "✅ Fixed $fixed segments with mediaId=0")
        fixed
    }
    suspend fun forceDeleteAllLyrics(): CleanupStats = withContext(Dispatchers.IO) {
        Log.d(TAG, "🗑️  Force deleting ALL lyrics transcripts and segments...")
        val allTranscripts = transcriptRepo.getAll().filter { it.source == "lyrics" }
        var transcriptsDeleted = 0
        var segmentsDeleted = 0
        allTranscripts.forEach { transcript ->
            val segments = segmentRepo.getByTranscriptId(transcript.id)
            segments.forEach { segmentRepo.delete(it.id); segmentsDeleted++ }
            transcriptRepo.delete(transcript.id)
            transcriptsDeleted++
        }
        Log.d(TAG, "✅ Force delete complete: ${transcriptsDeleted} transcripts deleted, ${segmentsDeleted} segments deleted")
        CleanupStats(transcriptsDeleted, segmentsDeleted, 0)
    }
}
