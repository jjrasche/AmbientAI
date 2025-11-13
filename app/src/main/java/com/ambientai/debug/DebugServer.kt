package com.ambientai.debug

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebugServer @Inject constructor(
    private val executor: DebugCommandExecutor,
    private val transcriptRepo: com.ambientai.data.repositories.ITranscriptRepository,
    private val workflowRepo: com.ambientai.data.repositories.IWorkflowDefinitionRepository,
    private val sttSimulator: SttSimulator,
    private val workflowSeeder: com.ambientai.data.WorkflowSeeder,
    private val mediaHistoryRepo: com.ambientai.data.repositories.IMediaHistoryRepository
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
                else -> notFoundResponse()
            }
        } catch (e: Exception) {
            errorResponse(e)
        }
    }

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
            <h2>Quick Commands:</h2>
            <pre>
curl http://localhost:8080/api/status
curl http://localhost:8080/api/workflows
curl -X POST http://localhost:8080/api/workflows/reseed
curl http://localhost:8080/api/transcripts?limit=5
curl -X POST http://localhost:8080/api/command -d '{"cmd":"ping"}'
curl -X POST http://localhost:8080/api/workflow/trigger/play_music
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
}

