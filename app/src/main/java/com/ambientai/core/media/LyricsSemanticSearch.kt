package com.ambientai.core.media

import android.util.Log
import com.ambientai.data.entities.Media
import com.ambientai.data.entities.TranscriptSegment
import com.ambientai.data.repositories.IMediaRepository
import com.ambientai.data.repositories.ITranscriptSegmentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LyricsSemanticSearch @Inject constructor(
    private val segmentRepo: ITranscriptSegmentRepository,
    private val mediaRepo: IMediaRepository,
    private val textEmbedder: TextEmbedder
) {
    companion object { private const val TAG = "LyricsSemanticSearch" }
    data class LyricsMatch(val media: Media, val segment: TranscriptSegment, val similarity: Float)
    suspend fun search(query: String, maxResults: Int = 10): List<LyricsMatch> = withContext(Dispatchers.IO) {
        Log.d(TAG, "🔍 Searching lyrics for: \"$query\" (max=$maxResults)")
        if (!textEmbedder.initialize()) { Log.e(TAG, "Failed to initialize embedder"); return@withContext emptyList() }
        val queryEmbedding = textEmbedder.embed(query)
        if (queryEmbedding == null) { Log.e(TAG, "Failed to embed query"); return@withContext emptyList() }
        Log.d(TAG, "Query embedded successfully (${queryEmbedding.size} dimensions)")
        val matches = segmentRepo.semanticSearchAllMedia(queryEmbedding, maxResults)
        Log.d(TAG, "Repository returned ${matches.size} segment matches")
        val results = matches.mapNotNull { segment -> Log.d(TAG, "  Processing segment ${segment.id} (mediaId=${segment.mediaId}, hasEmbedding=${segment.embedding != null})"); val media = mediaRepo.getById(segment.mediaId) ?: run { Log.w(TAG, "  Media not found for segment ${segment.id}"); return@mapNotNull null }; val embedding = segment.embedding ?: run { Log.w(TAG, "  Segment ${segment.id} has null embedding"); return@mapNotNull null }; LyricsMatch(media, segment, calculateSimilarity(queryEmbedding, embedding)) }
        Log.d(TAG, "✓ Found ${results.size} lyric matches")
        results.forEach { Log.d(TAG, "  ${it.similarity.format()} - ${it.media.title} - \"${it.segment.text.take(80)}...\"") }
        results
    }
    private fun calculateSimilarity(a: FloatArray, b: FloatArray): Float { if (a.size != b.size) return 0f; var dotProduct = 0f; var normA = 0f; var normB = 0f; for (i in a.indices) { dotProduct += a[i] * b[i]; normA += a[i] * a[i]; normB += b[i] * b[i] }; return if (normA == 0f || normB == 0f) 0f else dotProduct / (kotlin.math.sqrt(normA) * kotlin.math.sqrt(normB)) }
    private fun Float.format() = "%.3f".format(this)
}
