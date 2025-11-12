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
    private val workflowRepo: com.ambientai.data.repositories.IWorkflowDefinitionRepository
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
            Log.e(TAG, "✖ Failed to start debug server", e)
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
                uri == "/api/transcripts" -> handleTranscripts(session)
                uri == "/api/command" && method == Method.POST -> handleCommand(session)
                uri.startsWith("/api/workflow/trigger/") -> handleTriggerWorkflow(uri.substringAfterLast("/"))
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
                <li><a href="/api/transcripts?limit=10">/api/transcripts?limit=N</a> - Get recent transcripts</li>
                <li>POST /api/command - Execute debug command (body: {"cmd": "..."})</li>
                <li>POST /api/workflow/trigger/{name} - Trigger workflow by name</li>
            </ul>
            <h2>Quick Commands:</h2>
            <pre>
curl http://localhost:8080/api/status
curl http://localhost:8080/api/workflows
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
}
