package com.ambientai.core.media

import android.util.Log
import com.ambientai.data.entities.Media
import com.ambientai.data.repositories.IMediaRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

@Singleton
class MediaSemanticSearch @Inject constructor(
    private val mediaRepo: IMediaRepository,
    private val textEmbedder: TextEmbedder
) {
    companion object { private const val TAG = "MediaSemanticSearch" }
    data class SearchResult(val media: Media, val score: Float)
    suspend fun search(query: String, maxResults: Int = 5, minScore: Float = 0.3f): List<SearchResult> = withContext(Dispatchers.IO) {
        Log.d(TAG, "🔍 Searching library for: $query (max=$maxResults, minScore=$minScore)")
        val queryEmbedding = textEmbedder.embed(query)
        if (queryEmbedding == null) { Log.e(TAG, "Failed to embed query"); return@withContext emptyList() }
        val allMedia = mediaRepo.getAll()
        if (allMedia.isEmpty()) { Log.d(TAG, "No media in library"); return@withContext emptyList() }
        val results = allMedia.mapNotNull { media -> val text = buildSearchableText(media); val embedding = textEmbedder.embed(text) ?: return@mapNotNull null; val score = cosineSimilarity(queryEmbedding, embedding); if (score >= minScore) SearchResult(media, score) else null }.sortedByDescending { it.score }.take(maxResults)
        Log.d(TAG, "✓ Found ${results.size} results (top score: ${results.firstOrNull()?.score ?: 0f})")
        results
    }
    private fun buildSearchableText(media: Media) = buildString { append(media.title); media.channelName?.let { append(" "); append(it) }; if (media.sourceType == "youtube") append(" youtube video") }
    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float { if (a.size != b.size) return 0f; var dotProduct = 0f; var normA = 0f; var normB = 0f; for (i in a.indices) { dotProduct += a[i] * b[i]; normA += a[i] * a[i]; normB += b[i] * b[i] }; return if (normA == 0f || normB == 0f) 0f else dotProduct / (sqrt(normA) * sqrt(normB)) }
}
