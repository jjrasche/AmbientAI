package com.ambientai.core.enrichment

import android.util.Log
import com.ambientai.core.media.GeniusApiService
import com.ambientai.core.media.SmartSegmenter
import com.ambientai.data.entities.MediaTranscript
import com.ambientai.data.repositories.IMediaRepository
import com.ambientai.data.repositories.IMediaTranscriptRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaEnrichmentService @Inject constructor(
    private val mediaRepo: IMediaRepository,
    private val transcriptRepo: IMediaTranscriptRepository,
    private val geniusApi: GeniusApiService,
    private val segmenter: SmartSegmenter
) {
    companion object { private const val TAG = "MediaEnrichment"; private const val MAX_DAILY_REQUESTS = 500 }
    private val enrichmentScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val _status = MutableStateFlow(EnrichmentStatus())
    val status: StateFlow<EnrichmentStatus> = _status
    data class EnrichmentStatus(val isRunning: Boolean = false, val totalMedia: Int = 0, val enrichedCount: Int = 0, val todayRequestCount: Int = 0, val lastError: String? = null, val lastEnrichedTitle: String? = null)
    private var lastRequestDate = ""
    private fun resetDailyCounterIfNeeded() { val today = java.time.LocalDate.now().toString(); if (today != lastRequestDate) { _status.value = _status.value.copy(todayRequestCount = 0); lastRequestDate = today } }
    fun startBackgroundEnrichment() { if (_status.value.isRunning) { Log.d(TAG, "Enrichment already running"); return }; Log.d(TAG, "🎵 Starting background lyrics enrichment"); _status.value = _status.value.copy(isRunning = true); enrichmentScope.launch { enrichMediaLibrary() } }
    fun stopBackgroundEnrichment() { Log.d(TAG, "🛑 Stopping background enrichment"); _status.value = _status.value.copy(isRunning = false) }
    private suspend fun enrichMediaLibrary() { resetDailyCounterIfNeeded(); val allMedia = mediaRepo.getAll(); val unenrichedMedia = allMedia.filter { media -> val existingTranscripts = transcriptRepo.getAllByMediaId(media.id); !existingTranscripts.any { it.source == "lyrics" } }; Log.d(TAG, "📊 Total media: ${allMedia.size}, Unenriched: ${unenrichedMedia.size}"); _status.value = _status.value.copy(totalMedia = allMedia.size, enrichedCount = allMedia.size - unenrichedMedia.size); for (media in unenrichedMedia) { if (!_status.value.isRunning) { Log.d(TAG, "Enrichment stopped by user"); break }; if (_status.value.todayRequestCount >= MAX_DAILY_REQUESTS) { Log.d(TAG, "⚠ Daily API limit reached (${MAX_DAILY_REQUESTS}). Stopping enrichment."); _status.value = _status.value.copy(isRunning = false); break }; enrichSingleMedia(media.id); delay(2000) }; _status.value = _status.value.copy(isRunning = false); Log.d(TAG, "✅ Enrichment complete. Enriched: ${_status.value.enrichedCount}/${_status.value.totalMedia}") }
    suspend fun enrichSingleMedia(mediaId: Long): Boolean = withContext(Dispatchers.IO) {
        val media = mediaRepo.getById(mediaId) ?: return@withContext false
        val existingLyrics = transcriptRepo.getAllByMediaId(mediaId).find { it.source == "lyrics" }
        if (existingLyrics != null) { Log.d(TAG, "⏭ Already has lyrics: ${media.title}"); return@withContext true }
        Log.d(TAG, "🔍 Searching lyrics: ${media.title} - ${media.channelName}")
        resetDailyCounterIfNeeded()
        if (_status.value.todayRequestCount >= MAX_DAILY_REQUESTS) { Log.w(TAG, "Daily API limit reached"); return@withContext false }
        val searchQuery = "${media.title} ${media.channelName ?: ""}".trim()
        val searchResults = geniusApi.search(searchQuery, maxResults = 1)
        _status.value = _status.value.copy(todayRequestCount = _status.value.todayRequestCount + 1)
        if (searchResults.isEmpty()) { Log.d(TAG, "❌ No lyrics found for: ${media.title}"); _status.value = _status.value.copy(lastError = "No results: ${media.title}"); return@withContext false }
        val firstResult = searchResults.first()
        Log.d(TAG, "✓ Found: ${firstResult.title} - ${firstResult.artist}")
        delay(1000)
        resetDailyCounterIfNeeded()
        if (_status.value.todayRequestCount >= MAX_DAILY_REQUESTS) { Log.w(TAG, "Daily API limit reached"); return@withContext false }
        val lyrics = geniusApi.fetchLyrics(firstResult.songId)
        _status.value = _status.value.copy(todayRequestCount = _status.value.todayRequestCount + 1)
        if (lyrics == null) { Log.d(TAG, "❌ Failed to fetch lyrics for: ${firstResult.title}"); _status.value = _status.value.copy(lastError = "Fetch failed: ${firstResult.title}"); return@withContext false }
        Log.d(TAG, "✅ Fetched ${lyrics.length} chars of lyrics")
        val transcript = MediaTranscript(mediaId = mediaId, source = "lyrics", fetchedAt = System.currentTimeMillis()).also { transcriptRepo.save(it) }
        Log.d(TAG, "🔪 Segmenting lyrics...")
        segmenter.segmentLyrics(transcript.id, mediaId, lyrics)
        _status.value = _status.value.copy(enrichedCount = _status.value.enrichedCount + 1, lastEnrichedTitle = media.title)
        Log.d(TAG, "🎉 ENRICHED: ${media.title} (${_status.value.enrichedCount}/${_status.value.totalMedia})")
        true
    }
}
