package com.ambientai.core.media

import android.util.Log
import com.ambientai.data.entities.Media
import com.ambientai.data.entities.MediaPlaybackSession
import com.ambientai.data.entities.MediaTranscript
import com.ambientai.data.entities.TranscriptSegment
import com.ambientai.data.repositories.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaIntelligenceManager @Inject constructor(
    private val youtubeHandler: YouTubeHandler,
    private val textEmbedder: TextEmbedder,
    private val smartSegmenter: SmartSegmenter,
    private val mediaRepository: IMediaRepository,
    private val transcriptRepository: IMediaTranscriptRepository,
    private val segmentRepository: ITranscriptSegmentRepository,
    private val sessionRepository: IMediaPlaybackSessionRepository
) {
    companion object {
        private const val TAG = "MediaIntelligenceManager"
        private const val CONTEXT_WINDOW_MS = 120_000L
        private val timeFormat = SimpleDateFormat("mm:ss", Locale.US)
    }
    suspend fun initialize() { youtubeHandler.initialize(); textEmbedder.initialize() }
    suspend fun downloadYouTubeVideo(videoUrl: String, audioOnly: Boolean = true): Media? = withContext(Dispatchers.IO) { runCatching { val metadata = youtubeHandler.fetchMetadata(videoUrl) ?: return@withContext null; val audioFile = if (audioOnly) youtubeHandler.downloadAudio(videoUrl, metadata.id) else null; val thumbnailFile = youtubeHandler.downloadThumbnail(metadata.thumbnailUrl, metadata.id); val media = Media(title = metadata.title, sourceType = "youtube", mediaType = if (audioOnly) "audio" else "video", sourceUrl = videoUrl, duration = metadata.duration, thumbnailLocalPath = thumbnailFile?.absolutePath, channelName = metadata.channelName, publishDate = metadata.publishDate, localFilePath = audioFile?.absolutePath); mediaRepository.save(media); Log.d(TAG, "Downloaded YouTube ${if (audioOnly) "audio" else "video"}: ${metadata.title}"); processTranscript(media.id, videoUrl, metadata); media }.getOrElse { e -> Log.e(TAG, "Failed to download YouTube video", e); null } }
    private suspend fun processTranscript(mediaId: Long, videoUrl: String, metadata: YouTubeMetadata) = withContext(Dispatchers.IO) { runCatching { val captions = youtubeHandler.fetchCaptions(videoUrl, metadata.id) ?: return@withContext; val transcript = MediaTranscript(mediaId = mediaId, source = "youtube_captions"); val savedTranscript = transcriptRepository.save(transcript); val embedderFn: ((String) -> FloatArray)? = if (textEmbedder.embed("test") != null) { text -> textEmbedder.embed(text) ?: FloatArray(512) } else null; val segments = smartSegmenter.segment(captions, metadata.chapters, embedderFn).map { it.copy(transcriptId = savedTranscript.id) }; segmentRepository.saveAll(segments); generateEmbeddings(savedTranscript.id); Log.d(TAG, "Processed transcript: ${segments.size} segments") }.onFailure { e -> Log.e(TAG, "Failed to process transcript", e) } }
    private suspend fun generateEmbeddings(transcriptId: Long) = withContext(Dispatchers.IO) { runCatching { val segments = segmentRepository.getByTranscriptId(transcriptId); segments.forEach { segment -> if (segment.embedding == null) { val embedding = textEmbedder.embed(segment.text); if (embedding != null) segmentRepository.save(segment.copy(embedding = embedding)) } }; Log.d(TAG, "Generated embeddings for ${segments.size} segments") }.onFailure { e -> Log.e(TAG, "Failed to generate embeddings", e) } }
    fun getContextForQuestion(mediaId: Long, currentPositionMs: Long, question: String): String { val transcript = transcriptRepository.getByMediaId(mediaId) ?: return "No transcript available"; val currentSegment = segmentRepository.getAtPosition(transcript.id, currentPositionMs); val relevantSegments = if (hasTopicKeywords(question)) semanticSearch(transcript.id, question, topK = 3) else segmentRepository.getInWindow(transcript.id, currentPositionMs - CONTEXT_WINDOW_MS, currentPositionMs + CONTEXT_WINDOW_MS); return buildContext(currentSegment, relevantSegments, currentPositionMs) }
    private fun semanticSearch(transcriptId: Long, query: String, topK: Int): List<TranscriptSegment> { val queryEmbedding = textEmbedder.embed(query) ?: return emptyList(); return segmentRepository.semanticSearch(transcriptId, queryEmbedding, topK) }
    private fun hasTopicKeywords(question: String) = question.split(" ").any { it.length > 4 && !it.matches(Regex("what|where|when|how|why|about|the|that|this", RegexOption.IGNORE_CASE)) }
    private fun buildContext(currentSegment: TranscriptSegment?, relevantSegments: List<TranscriptSegment>, currentPositionMs: Long) = buildString { appendLine("Current position: ${formatTimestamp(currentPositionMs)}"); currentSegment?.let { appendLine("Current segment: ${it.text}"); appendLine() }; if (relevantSegments.isNotEmpty()) { appendLine("Relevant context:"); relevantSegments.forEach { segment -> appendLine("[${formatTimestamp(segment.startMs)}] ${segment.text}"); appendLine() } } }
    private fun formatTimestamp(ms: Long) = timeFormat.format(Date(ms))
    fun startPlaybackSession(mediaId: Long, startPositionMs: Long): MediaPlaybackSession = sessionRepository.save(MediaPlaybackSession(mediaId = mediaId, startPositionMs = startPositionMs, startTime = System.currentTimeMillis()))
    fun incrementSessionQuestions(sessionId: Long) { sessionRepository.incrementQuestions(sessionId) }
}
