package com.ambientai.debug

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebugServer @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val executor: DebugCommandExecutor,
    private val transcriptRepo: com.ambientai.data.repositories.ITranscriptRepository,
    private val workflowRepo: com.ambientai.data.repositories.IWorkflowDefinitionRepository,
    private val sttSimulator: SttSimulator,
    private val workflowSeeder: com.ambientai.data.WorkflowSeeder,
    private val mediaHistoryRepo: com.ambientai.data.repositories.IMediaHistoryRepository,
    private val mediaIntelligence: com.ambientai.core.media.MediaIntelligenceManager,
    private val mediaRepo: com.ambientai.data.repositories.IMediaRepository,
    private val youtubeSearch: com.ambientai.core.media.YouTubeSearchService,
    private val uiService: com.ambientai.core.ui.UiService,
    private val mediaWorkflowHandler: com.ambientai.core.media.MediaWorkflowHandler,
    private val llm: com.ambientai.core.llm.GroqLlmService,
    private val enrichmentService: com.ambientai.core.enrichment.MediaEnrichmentService,
    private val musicLibraryImporter: com.ambientai.core.music.MusicLibraryImporter,
    private val lyricsSearch: com.ambientai.core.media.LyricsSemanticSearch,
    private val enrichmentCleanup: com.ambientai.core.enrichment.EnrichmentCleanupService,
    private val mediaTranscriptRepo: com.ambientai.data.repositories.IMediaTranscriptRepository,
    private val segmentRepo: com.ambientai.data.repositories.ITranscriptSegmentRepository
) : NanoHTTPD(8080) {

    companion object {
        private const val TAG = "DebugServer"
    }

    private var isStarted = false

    fun startServer() {
        if (isStarted) return
        try {
            start(SOCKET_READ_TIMEOUT, false)
            isStarted = true
            Log.d(TAG, "🌐 DEBUG SERVER STARTED on http://localhost:8080")
            Log.d(TAG, "📍 Use: adb forward tcp:8080 tcp:8080")
        } catch (e: Exception) {
            Log.e(TAG, "✖ F`ailed to start debug server", e)
        }
    }

    fun stopServer() {
        if (!isStarted) return
        stop()
        isStarted = false
        Log.d(TAG, "🛑 DEBUG SERVER STOPPED")
    }

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method

        Log.d(TAG, "📡 ${method.name} $uri")

        return try {
            when {
                uri == "/" -> handleRoot()
                uri == "/lyrics" -> handleLyricsSearchPage()
                uri == "/api/status" -> handleStatus()
                uri == "/api/ping" -> jsonResponse(JSONObject().apply { put("status", "pong") })
                uri == "/api/workflows" -> handleWorkflows()
                uri == "/api/workflows/reseed" && method == Method.POST -> handleReseedWorkflows()
                uri == "/api/transcripts" -> handleTranscripts(session)
                uri == "/api/media_history" -> handleMediaHistory(session)
                uri == "/api/command" && method == Method.POST -> handleCommand(session)
                uri.startsWith("/api/workflow/trigger/") -> handleTriggerWorkflow(uri.substringAfterLast("/"))
                uri == "/api/test/partial" && method == Method.POST -> handleTestPartial(session)
                uri == "/api/test/sequence" && method == Method.POST -> handleTestSequence(session)
                uri == "/api/test/utterance_end" && method == Method.POST -> handleTestUtteranceEnd(session)
                uri == "/api/test/scenarios" -> handleTestScenarios()
                uri == "/api/config" -> handleGetConfig()
                uri == "/api/config/set" && method == Method.POST -> handleSetConfig(session)
                uri == "/api/media/download" && method == Method.POST -> handleMediaDownload(session)
                uri == "/api/media/library" -> handleMediaLibrary()
                uri == "/api/media/search" && method == Method.POST -> handleMediaSearch(session)
                uri.startsWith("/api/media/") && uri.endsWith("/context") -> handleMediaContext(session, uri)
                uri == "/api/ui/choice/submit" && method == Method.POST -> handleSubmitChoice(session)
                uri == "/api/ui/choice/state" -> handleChoiceState()
                uri == "/api/test/media/search_select" && method == Method.POST -> handleTestMediaSearchSelect(session)
                uri == "/api/media/search_library" && method == Method.POST -> handleSearchLibrary(session)
                uri == "/api/test/llm/intent" && method == Method.POST -> handleTestLlmIntent(session)
                uri == "/api/test/llm/match_eval" && method == Method.POST -> handleTestLlmMatchEval(session)
                uri == "/api/enrichment/start" && method == Method.POST -> handleEnrichmentStart()
                uri == "/api/enrichment/stop" && method == Method.POST -> handleEnrichmentStop()
                uri == "/api/enrichment/status" -> handleEnrichmentStatus()
                uri.startsWith("/api/enrichment/media/") && method == Method.POST -> handleEnrichmentSingle(uri)
                uri == "/api/enrichment/cleanup" && method == Method.POST -> handleEnrichmentCleanup()
                uri == "/api/enrichment/fix_mediaids" && method == Method.POST -> handleFixMediaIds()
                uri == "/api/enrichment/force_delete_lyrics" && method == Method.POST -> handleForceDeleteLyrics()
                uri == "/api/music/import" && method == Method.POST -> handleMusicImport()
                uri == "/api/media/transcripts" -> handleGetMediaTranscripts()
                uri == "/api/lyrics/search" && method == Method.POST -> handleLyricsSearch(session)
                else -> notFoundResponse()
            }
        } catch (e: Exception) {
            errorResponse(e)
        }
    }

    private fun handleLyricsSearchPage() = runCatching { val html = context.assets.open("lyrics_search.html").bufferedReader().use { it.readText() }; newFixedLengthResponse(Response.Status.OK, "text/html", html) }.getOrElse { e -> Log.e(TAG, "Error loading lyrics search page", e); newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error loading page: ${e.message}") }
    private fun handleRoot() = newFixedLengthResponse(
        Response.Status.OK,
        "text/html",
        """
        <html>
        <head><title>AmbientAI Debug Server</title></head>
        <body>
            <h1>🤖 AmbientAI Debug Server</h1>
            <h2>Available Endpoints:</h2>
            <ul>
                <li><a href="/lyrics">🎵 /lyrics</a> - Interactive lyrics search UI</li>
                <li><a href="/api/ping">/api/ping</a> - Test connection</li>
                <li><a href="/api/status">/api/status</a> - Get service status</li>
                <li><a href="/api/workflows">/api/workflows</a> - List workflows</li>
                <li>POST /api/workflows/reseed - Delete all workflows and reseed from code</li>
                <li><a href="/api/transcripts?limit=10">/api/transcripts?limit=N</a> - Get recent transcripts</li>
                <li><a href="/api/media_history?limit=50">/api/media_history?limit=N</a> - Get music listening history</li>
                <li>POST /api/command - Execute debug command (body: {"cmd": "..."})</li>
                <li>POST /api/workflow/trigger/{name} - Trigger workflow by name</li>
            </ul>
            <h2>STT Simulation & Testing:</h2>
            <ul>
                <li><a href="/api/test/scenarios">/api/test/scenarios</a> - Get predefined test scenarios</li>
                <li>POST /api/test/partial - Test partial transcript routing</li>
                <li>POST /api/test/sequence - Test sequence of partials</li>
                <li>POST /api/test/utterance_end - Test final transcript</li>
                <li><a href="/api/config">/api/config</a> - Get current routing config</li>
                <li>POST /api/config/set - Update routing config</li>
            </ul>
            <h2>Music Library & Enrichment:</h2>
            <ul>
                <li>POST /api/music/import - Import all songs from music folder to Media entities</li>
                <li><a href="/api/enrichment/status">/api/enrichment/status</a> - Check lyrics enrichment status</li>
                <li>POST /api/enrichment/start - Start background lyrics enrichment</li>
                <li>POST /api/enrichment/stop - Stop background enrichment</li>
                <li>POST /api/enrichment/cleanup - Delete transcripts/segments without embeddings</li>
                <li>POST /api/lyrics/search - Semantic search on lyrics (requires embeddings)</li>
            </ul>
            <h2>Quick Commands:</h2>
            <pre>
curl http://localhost:8080/api/status
curl http://localhost:8080/api/workflows
curl -X POST http://localhost:8080/api/workflows/reseed
curl http://localhost:8080/api/transcripts?limit=5
curl -X POST http://localhost:8080/api/command -d '{"cmd":"ping"}'
curl -X POST http://localhost:8080/api/workflow/trigger/play_music
curl -X POST http://localhost:8080/api/music/import
curl -X POST http://localhost:8080/api/enrichment/start
curl http://localhost:8080/api/enrichment/status
curl -X POST http://localhost:8080/api/lyrics/search -d '{"query":"heartbreak","max_results":5}'
            </pre>
        </body>
        </html>
        """.trimIndent()
    )

    private fun handleStatus(): Response {
        val result = executor.execute("status")
        return jsonResponse(JSONObject(result))
    }

    private fun handleWorkflows(): Response {
        val workflows = workflowRepo.getEnabled()
        val json = JSONArray().apply {
            workflows.forEach { workflow ->
                put(JSONObject().apply {
                    put("id", workflow.id)
                    put("name", workflow.name)
                    put("enabled", workflow.enabled)
                    put("definition", workflow.definition)
                })
            }
        }
        return jsonResponse(json)
    }
    private fun handleReseedWorkflows(): Response {
        val countBefore = workflowRepo.count()
        workflowSeeder.reseedAll()
        val countAfter = workflowRepo.count()
        return jsonResponse(JSONObject().apply {
            put("status", "success")
            put("workflows_before", countBefore)
            put("workflows_after", countAfter)
            put("message", "Workflows reseeded from code")
        })
    }

    private fun handleTranscripts(session: IHTTPSession): Response {
        val params = session.parameters
        val limit = params["limit"]?.firstOrNull()?.toIntOrNull() ?: 10
        val transcripts = transcriptRepo.getAll().take(limit)
        val json = JSONArray().apply {
            transcripts.forEach { transcript ->
                put(JSONObject().apply {
                    put("id", transcript.id)
                    put("text", transcript.text)
                    put("timestamp", transcript.timestamp)
                    put("audioFilePath", transcript.audioFilePath)
                    put("excludeFromContext", transcript.excludeFromContext)
                })
            }
        }
        return jsonResponse(json)
    }
    private fun handleMediaHistory(session: IHTTPSession): Response {
        val params = session.parameters
        val limit = params["limit"]?.firstOrNull()?.toIntOrNull() ?: 50
        val history = mediaHistoryRepo.getRecent(limit)
        val json = JSONArray().apply {
            history.forEach { entry ->
                put(JSONObject().apply {
                    put("id", entry.id)
                    put("mediaPath", entry.mediaPath)
                    put("mediaType", entry.mediaType)
                    put("timestamp", entry.timestamp)
                    put("durationPlayedMs", entry.durationPlayedMs)
                    val songName = entry.mediaPath.substringAfterLast("/").substringBeforeLast(".")
                    put("songName", songName)
                })
            }
        }
        return jsonResponse(json)
    }

    private fun handleCommand(session: IHTTPSession): Response {
        val body = getRequestBody(session)
        val json = JSONObject(body)
        val cmd = json.getString("cmd")
        val result = executor.execute(cmd)
        return jsonResponse(JSONObject().apply {
            put("command", cmd)
            put("result", result)
        })
    }

    private fun handleTriggerWorkflow(workflowName: String): Response {
        val result = executor.execute("trigger:$workflowName")
        return jsonResponse(JSONObject().apply {
            put("workflow", workflowName)
            put("result", result)
        })
    }

    private fun getRequestBody(session: IHTTPSession): String {
        val map = mutableMapOf<String, String>()
        session.parseBody(map)
        return map["postData"] ?: ""
    }

    private fun jsonResponse(json: JSONObject) = newFixedLengthResponse(
        Response.Status.OK,
        "application/json",
        json.toString(2)
    )

    private fun jsonResponse(json: JSONArray) = newFixedLengthResponse(
        Response.Status.OK,
        "application/json",
        json.toString(2)
    )

    private fun notFoundResponse() = newFixedLengthResponse(
        Response.Status.NOT_FOUND,
        "application/json",
        JSONObject().apply { put("error", "Not found") }.toString()
    )

    private fun errorResponse(e: Exception) = newFixedLengthResponse(
        Response.Status.INTERNAL_ERROR,
        "application/json",
        JSONObject().apply {
            put("error", e.message ?: "Unknown error")
            put("type", e::class.simpleName)
        }.toString()
    )
    private fun handleTestPartial(session: IHTTPSession): Response {
        val body = getRequestBody(session)
        val json = JSONObject(body)
        val text = json.getString("text")
        val elapsedMs = json.getLong("elapsed_ms")
        val confidence = json.optDouble("confidence", 0.85).toFloat()
        val result = sttSimulator.testPartialRouting(text, elapsedMs, confidence)
        return jsonResponse(result.toJson())
    }
    private fun handleTestSequence(session: IHTTPSession): Response {
        val body = getRequestBody(session)
        val json = JSONObject(body)
        val partialsArray = json.getJSONArray("partials")
        val partials = mutableListOf<PartialTranscript>()
        for (i in 0 until partialsArray.length()) {
            val p = partialsArray.getJSONObject(i)
            partials.add(PartialTranscript(
                text = p.getString("text"),
                elapsedMs = p.getLong("elapsed_ms"),
                confidence = p.optDouble("confidence", 0.85).toFloat()
            ))
        }
        val result = sttSimulator.testSequence(partials)
        return jsonResponse(result.toJson())
    }
    private fun handleTestUtteranceEnd(session: IHTTPSession): Response {
        val body = getRequestBody(session)
        val json = JSONObject(body)
        val text = json.getString("text")
        val elapsedMs = json.getLong("elapsed_ms")
        val result = sttSimulator.testUtteranceEnd(text, elapsedMs)
        return jsonResponse(result.toJson())
    }
    private fun handleTestScenarios(): Response {
        val scenarios = sttSimulator.generateScenarios()
        val json = JSONArray().apply {
            scenarios.forEach { put(it.toJson()) }
        }
        return jsonResponse(json)
    }
    private fun handleGetConfig(): Response {
        val config = JSONObject().apply {
            put("MIN_ELAPSED_BEFORE_ROUTING_MS", com.ambientai.core.workflow.RoutingConfig.MIN_ELAPSED_BEFORE_ROUTING_MS)
            put("TIER1_TIMEOUT_MS", com.ambientai.core.workflow.RoutingConfig.TIER1_TIMEOUT_MS)
            put("TIER2_TIMEOUT_MS", com.ambientai.core.workflow.RoutingConfig.TIER2_TIMEOUT_MS)
            put("TIER3_TIMEOUT_MS", com.ambientai.core.workflow.RoutingConfig.TIER3_TIMEOUT_MS)
            put("TIER4_TIMEOUT_MS", com.ambientai.core.workflow.RoutingConfig.TIER4_TIMEOUT_MS)
            put("INSTANT_COMMAND_MAX_WORDS", com.ambientai.core.workflow.RoutingConfig.INSTANT_COMMAND_MAX_WORDS)
            put("QUICK_COMMAND_MAX_WORDS", com.ambientai.core.workflow.RoutingConfig.QUICK_COMMAND_MAX_WORDS)
            put("COMPLEX_COMMAND_MAX_WORDS", com.ambientai.core.workflow.RoutingConfig.COMPLEX_COMMAND_MAX_WORDS)
            put("CONVERSATIONAL_MIN_WORDS", com.ambientai.core.workflow.RoutingConfig.CONVERSATIONAL_MIN_WORDS)
            put("MIN_CONFIDENCE_INSTANT", com.ambientai.core.workflow.RoutingConfig.MIN_CONFIDENCE_INSTANT)
            put("MIN_CONFIDENCE_QUICK", com.ambientai.core.workflow.RoutingConfig.MIN_CONFIDENCE_QUICK)
            put("MIN_CONFIDENCE_COMPLEX", com.ambientai.core.workflow.RoutingConfig.MIN_CONFIDENCE_COMPLEX)
            put("features", JSONObject().apply {
                put("useAdaptiveTimeouts", com.ambientai.core.workflow.RoutingConfig.useAdaptiveTimeouts)
                put("useSemanticEndpointing", com.ambientai.core.workflow.RoutingConfig.useSemanticEndpointing)
                put("useUserProfiling", com.ambientai.core.workflow.RoutingConfig.useUserProfiling)
            })
        }
        return jsonResponse(config)
    }
    private fun handleSetConfig(session: IHTTPSession): Response {
        val body = getRequestBody(session)
        val json = JSONObject(body)
        // Note: This updates runtime config, not persisted
        // Would need to add mutable vars to RoutingConfig for this to work
        return jsonResponse(JSONObject().apply {
            put("status", "Config update not yet implemented")
            put("note", "RoutingConfig constants are immutable. Add mutable vars to enable runtime updates.")
        })
    }
    private fun handleMediaDownload(session: IHTTPSession): Response = runCatching { val body = getRequestBody(session); val json = JSONObject(body); val url = json.getString("url"); val audioOnly = json.optBoolean("audio_only", true); val media = kotlinx.coroutines.runBlocking { mediaIntelligence.downloadYouTubeVideo(url, audioOnly) }; if (media != null) jsonResponse(JSONObject().apply { put("success", true); put("media_id", media.id); put("title", media.title); put("duration_ms", media.duration) }) else jsonResponse(JSONObject().apply { put("success", false); put("error", "MediaIntelligenceManager returned null - check logcat") }) }.getOrElse { e -> Log.e(TAG, "handleMediaDownload error", e); jsonResponse(JSONObject().apply { put("success", false); put("error", e.message ?: "Unknown error"); put("exception", e.javaClass.simpleName) }) }
    private fun handleMediaLibrary(): Response { val allMedia = mediaRepo.getAll(); val json = JSONArray(); allMedia.forEach { media -> json.put(JSONObject().apply { put("id", media.id); put("title", media.title); put("source_type", media.sourceType); put("media_type", media.mediaType); put("duration_ms", media.duration); put("local_path", media.localFilePath ?: "null") }) }; return jsonResponse(json) }
    private fun handleMediaSearch(session: IHTTPSession): Response = runCatching { val body = getRequestBody(session); val json = JSONObject(body); val query = json.getString("query"); val maxResults = json.optInt("max_results", 10); val searchResponse = kotlinx.coroutines.runBlocking { youtubeSearch.search(query, maxResults) }; if (searchResponse != null) { val resultsJson = JSONArray(); searchResponse.results.forEach { result -> resultsJson.put(JSONObject().apply { put("video_id", result.videoId); put("title", result.title); put("channel", result.channelName); put("thumbnail", result.thumbnailUrl); put("duration_ms", result.duration); put("views", result.viewCount); put("published", result.publishedDate); put("description", result.description) }) }; jsonResponse(JSONObject().apply { put("query", searchResponse.query); put("total_results", searchResponse.totalResults); put("results", resultsJson) }) } else { jsonResponse(JSONObject().apply { put("success", false); put("error", "Search failed") }) } }.getOrElse { e -> Log.e(TAG, "handleMediaSearch error", e); jsonResponse(JSONObject().apply { put("success", false); put("error", e.message ?: "Unknown error") }) }
    private fun handleMediaContext(session: IHTTPSession, uri: String): Response { val mediaId = uri.substringAfter("/api/media/").substringBefore("/context").toLongOrNull() ?: return errorResponse(Exception("Invalid media ID")); val body = getRequestBody(session); val json = JSONObject(body); val positionMs = json.getLong("position_ms"); val question = json.getString("question"); val context = mediaIntelligence.getContextForQuestion(mediaId, positionMs, question); return jsonResponse(JSONObject().apply { put("media_id", mediaId); put("position_ms", positionMs); put("question", question); put("context", context) }) }
    private fun handleSubmitChoice(session: IHTTPSession): Response = runCatching { val body = getRequestBody(session); val json = JSONObject(body); val index = json.getInt("index"); uiService.submitChoice(index); jsonResponse(JSONObject().apply { put("success", true); put("submitted_index", index) }) }.getOrElse { e -> Log.e(TAG, "handleSubmitChoice error", e); jsonResponse(JSONObject().apply { put("success", false); put("error", e.message ?: "Unknown error") }) }
    private fun handleChoiceState(): Response { val state = uiService.choiceState.value; return if (state != null) jsonResponse(JSONObject().apply { put("active", true); put("title", state.title); put("message", state.message); put("options", JSONArray(state.options)) }) else jsonResponse(JSONObject().apply { put("active", false) }) }
    private fun handleTestMediaSearchSelect(session: IHTTPSession): Response = runCatching { val body = getRequestBody(session); val json = JSONObject(body); val query = json.getString("query"); val maxResults = json.optInt("max_results", 5); val autoSelect = json.optInt("auto_select_index", -1); val delayMs = json.optLong("auto_select_delay_ms", 1000); Log.d(TAG, "🧪 TESTING media.searchAndSelect: query=$query, autoSelect=$autoSelect, delay=${delayMs}ms"); Thread { var waited = 0L; while (uiService.choiceState.value == null && waited < 10000) { Thread.sleep(100); waited += 100 }; if (uiService.choiceState.value != null) { Log.d(TAG, "⏳ Choice UI active, waiting ${delayMs}ms before auto-submit"); Thread.sleep(delayMs); if (autoSelect >= 0) { Log.d(TAG, "🤖 AUTO-SUBMITTING CHOICE: $autoSelect"); uiService.submitChoice(autoSelect) } } }.start(); val input = JSONObject().apply { put("query", query); put("max_results", maxResults) }; val result = kotlinx.coroutines.runBlocking { mediaWorkflowHandler.execute("media.searchAndSelect", input) }; jsonResponse(result) }.getOrElse { e -> Log.e(TAG, "handleTestMediaSearchSelect error", e); jsonResponse(JSONObject().apply { put("success", false); put("error", e.message ?: "Unknown error") }) }
    private fun handleSearchLibrary(session: IHTTPSession): Response = runCatching { val body = getRequestBody(session); val json = JSONObject(body); val query = json.getString("query"); val maxResults = json.optInt("max_results", 5); val minScore = json.optDouble("min_score", 0.3); val input = JSONObject().apply { put("query", query); put("max_results", maxResults); put("min_score", minScore) }; val result = kotlinx.coroutines.runBlocking { mediaWorkflowHandler.execute("media.searchLibrary", input) }; jsonResponse(result) }.getOrElse { e -> Log.e(TAG, "handleSearchLibrary error", e); jsonResponse(JSONObject().apply { put("success", false); put("error", e.message ?: "Unknown error") }) }
    private fun handleTestLlmIntent(session: IHTTPSession): Response = runCatching { val body = getRequestBody(session); val json = JSONObject(body); val transcript = json.getString("transcript"); val systemPrompt = """Analyze the user's play request. Return ONLY valid JSON with no markdown fences or additional text.

If the request is NOT about playing media/audio/video content (e.g., idioms like 'play it safe', activities like 'play with kids'), return: {"content_type": "not_media", "query_specificity": "n/a", "artist": null, "title": null, "semantic_query": null, "needs_recency": false, "reasoning": "<explanation>"}

If it IS a media playback request, return:
{
  "content_type": "song|album|artist|podcast|educational|specific_memory|music",
  "query_specificity": "exact|partial|vague",
  "artist": "<artist name or null>",
  "title": "<title or null>",
  "semantic_query": "<search query for semantic search>",
  "needs_recency": true|false,
  "reasoning": "<brief explanation>"
}"""; Log.d(TAG, "🧪 TESTING LLM intent analysis: $transcript"); val input = JSONObject().apply { put("systemPrompt", systemPrompt); put("userPrompt", transcript); put("temperature", 0.3); put("maxTokens", 200) }; val result = kotlinx.coroutines.runBlocking { llm.execute("llm.prompt", input) }; jsonResponse(JSONObject().apply { put("transcript", transcript); put("llm_response", result); put("success", true) }) }.getOrElse { e -> Log.e(TAG, "handleTestLlmIntent error", e); jsonResponse(JSONObject().apply { put("success", false); put("error", e.message ?: "Unknown error") }) }
    private fun handleTestLlmMatchEval(session: IHTTPSession): Response = runCatching { val body = getRequestBody(session); val json = JSONObject(body); val transcript = json.getString("transcript"); val intent = json.getJSONObject("intent"); val libraryResults = json.getJSONArray("library_results"); val systemPrompt = """Evaluate if library search results satisfy the user's original request. Return ONLY valid JSON with no markdown fences.

{
  "match_quality": "excellent|good|poor|none",
  "best_match_id": <media_id or null>,
  "confidence": 0.0-1.0,
  "should_search_youtube": true|false,
  "reasoning": "<explanation>"
}

Guidelines:
- excellent: Play immediately (exact match, high score >0.8, or strong specific memory match >0.75)
- good: Offer choice between library and YouTube (partial match, score 0.5-0.8, or user wants 'newest' but we have old version)
- poor: Skip to YouTube (score <0.5, or user wants album but we only have one song)
- none: No library results, go straight to YouTube

Consider:
- Does user want 'newest/latest'? Check if library content is recent enough
- Does user want full album but we only have singles?
- Does user want unheard content? Check playback history
- Is semantic similarity score strong enough for the query type?"""; Log.d(TAG, "🧪 TESTING LLM match evaluation"); val userPrompt = "User request: $transcript\n\nIntent analysis: $intent\n\nLibrary results:\n$libraryResults"; val input = JSONObject().apply { put("systemPrompt", systemPrompt); put("userPrompt", userPrompt); put("temperature", 0.3); put("maxTokens", 200) }; val result = kotlinx.coroutines.runBlocking { llm.execute("llm.prompt", input) }; jsonResponse(JSONObject().apply { put("transcript", transcript); put("intent", intent); put("library_results", libraryResults); put("llm_response", result); put("success", true) }) }.getOrElse { e -> Log.e(TAG, "handleTestLlmMatchEval error", e); jsonResponse(JSONObject().apply { put("success", false); put("error", e.message ?: "Unknown error") }) }
    private fun handleEnrichmentStart(): Response { enrichmentService.startBackgroundEnrichment(); return jsonResponse(JSONObject().apply { put("success", true); put("message", "Enrichment started") }) }
    private fun handleEnrichmentStop(): Response { enrichmentService.stopBackgroundEnrichment(); return jsonResponse(JSONObject().apply { put("success", true); put("message", "Enrichment stopped") }) }
    private fun handleEnrichmentStatus(): Response { val status = enrichmentService.status.value; return jsonResponse(JSONObject().apply { put("is_running", status.isRunning); put("total_media", status.totalMedia); put("enriched_count", status.enrichedCount); put("today_request_count", status.todayRequestCount); put("last_enriched_title", status.lastEnrichedTitle ?: "null"); put("last_error", status.lastError ?: "null") }) }
    private fun handleEnrichmentSingle(uri: String): Response = runCatching { val mediaId = uri.substringAfterLast("/").toLongOrNull() ?: return@runCatching jsonResponse(JSONObject().apply { put("success", false); put("error", "Invalid media ID") }); val success = kotlinx.coroutines.runBlocking { enrichmentService.enrichSingleMedia(mediaId) }; jsonResponse(JSONObject().apply { put("success", success); put("media_id", mediaId) }) }.getOrElse { e -> Log.e(TAG, "handleEnrichmentSingle error", e); jsonResponse(JSONObject().apply { put("success", false); put("error", e.message ?: "Unknown error") }) }
    private fun handleEnrichmentCleanup(): Response = runCatching { Log.d(TAG, "🧹 Starting enrichment cleanup..."); val stats = kotlinx.coroutines.runBlocking { enrichmentCleanup.cleanupBadEnrichment() }; jsonResponse(JSONObject().apply { put("success", true); put("transcripts_deleted", stats.transcriptsDeleted); put("segments_deleted", stats.segmentsDeleted); put("good_segments_kept", stats.goodSegments); put("message", "Cleaned up ${stats.transcriptsDeleted} bad transcripts and ${stats.segmentsDeleted} bad segments. Kept ${stats.goodSegments} good segments.") }) }.getOrElse { e -> Log.e(TAG, "handleEnrichmentCleanup error", e); jsonResponse(JSONObject().apply { put("success", false); put("error", e.message ?: "Unknown error") }) }
    private fun handleFixMediaIds(): Response = runCatching { Log.d(TAG, "🔧 Fixing mediaId=0 segments..."); val fixed = kotlinx.coroutines.runBlocking { enrichmentCleanup.fixMediaIds() }; jsonResponse(JSONObject().apply { put("success", true); put("segments_fixed", fixed); put("message", "Fixed $fixed segments with mediaId=0") }) }.getOrElse { e -> Log.e(TAG, "handleFixMediaIds error", e); jsonResponse(JSONObject().apply { put("success", false); put("error", e.message ?: "Unknown error") }) }
    private fun handleForceDeleteLyrics(): Response = runCatching { Log.d(TAG, "🗑️  Force deleting all lyrics..."); val stats = kotlinx.coroutines.runBlocking { enrichmentCleanup.forceDeleteAllLyrics() }; jsonResponse(JSONObject().apply { put("success", true); put("transcripts_deleted", stats.transcriptsDeleted); put("segments_deleted", stats.segmentsDeleted); put("message", "Force deleted ${stats.transcriptsDeleted} lyrics transcripts and ${stats.segmentsDeleted} segments. Ready for re-enrichment.") }) }.getOrElse { e -> Log.e(TAG, "handleForceDeleteLyrics error", e); jsonResponse(JSONObject().apply { put("success", false); put("error", e.message ?: "Unknown error") }) }
    private fun handleMusicImport(): Response = runCatching { Log.d(TAG, "🎵 Importing music library to Media entities..."); val result = musicLibraryImporter.importAllSongs(); jsonResponse(JSONObject().apply { put("success", true); put("total_songs", result.totalSongs); put("created", result.created); put("skipped", result.skipped); put("message", "Imported ${result.created} new songs (${result.skipped} already existed)") }) }.getOrElse { e -> Log.e(TAG, "handleMusicImport error", e); jsonResponse(JSONObject().apply { put("success", false); put("error", e.message ?: "Unknown error") }) }
    private fun handleGetMediaTranscripts(): Response = runCatching { Log.d(TAG, "📋 Getting all media transcripts..."); val allTranscripts = kotlinx.coroutines.runBlocking { mediaTranscriptRepo.getAll() }; val transcriptsJson = JSONArray(); allTranscripts.forEach { transcript -> val media = mediaRepo.getById(transcript.mediaId); val segments = segmentRepo.getByTranscriptId(transcript.id); transcriptsJson.put(JSONObject().apply { put("transcript_id", transcript.id); put("media_id", transcript.mediaId); put("source", transcript.source); put("fetched_at", transcript.fetchedAt); put("song_title", media?.title ?: "Unknown"); put("artist", media?.channelName ?: "Unknown"); put("segment_count", segments.size); put("has_embeddings", segments.any { it.embedding != null }); put("embedding_model", segments.firstOrNull()?.embeddingModel ?: "null"); put("segments", JSONArray().apply { segments.forEach { seg -> put(JSONObject().apply { put("segment_id", seg.id); put("text", seg.text); put("start_ms", seg.startMs); put("end_ms", seg.endMs); put("has_embedding", seg.embedding != null); put("embedding_dims", seg.embedding?.size ?: 0) }) } }) }) }; jsonResponse(JSONObject().apply { put("success", true); put("total_transcripts", transcriptsJson.length()); put("transcripts", transcriptsJson) }) }.getOrElse { e -> Log.e(TAG, "handleGetMediaTranscripts error", e); jsonResponse(JSONObject().apply { put("success", false); put("error", e.message ?: "Unknown error") }) }
    private fun handleLyricsSearch(session: IHTTPSession): Response = runCatching { val body = getRequestBody(session); val json = JSONObject(body); val query = json.getString("query"); val maxResults = json.optInt("max_results", 10); Log.d(TAG, "🎵 Searching lyrics: $query"); val results = kotlinx.coroutines.runBlocking { lyricsSearch.search(query, maxResults) }; val resultsJson = JSONArray(); results.forEach { match -> resultsJson.put(JSONObject().apply { put("media_id", match.media.id); put("title", match.media.title); put("artist", match.media.channelName); put("similarity", match.similarity); put("lyric_snippet", match.segment.text.take(200)); put("full_segment", match.segment.text) }) }; jsonResponse(JSONObject().apply { put("success", true); put("query", query); put("total_results", results.size); put("results", resultsJson) }) }.getOrElse { e -> Log.e(TAG, "handleLyricsSearch error", e); jsonResponse(JSONObject().apply { put("success", false); put("error", e.message ?: "Unknown error") }) }
}

