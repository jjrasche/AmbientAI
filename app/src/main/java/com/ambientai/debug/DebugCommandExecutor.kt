package com.ambientai.debug

import android.content.Context
import android.content.Intent
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebugCommandExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workflowRepo: com.ambientai.data.repositories.IWorkflowDefinitionRepository,
    private val transcriptRepo: com.ambientai.data.repositories.ITranscriptRepository
) {
    fun execute(cmd: String): String = when {
        cmd == "status" -> getStatus()
        cmd == "ping" -> "pong"
        cmd.startsWith("logs:") -> getLogs(cmd.substringAfter("logs:").toIntOrNull() ?: 50)
        cmd == "workflows" -> getWorkflows()
        cmd.startsWith("trigger:") -> triggerWorkflow(cmd.substringAfter("trigger:"))
        cmd.startsWith("transcripts:") -> getTranscripts(cmd.substringAfter("transcripts:").toIntOrNull() ?: 10)
        cmd == "help" -> getHelp()
        else -> "Unknown command: $cmd. Use 'help' for available commands."
    }
    private fun getStatus() = JSONObject().apply {
        put("service_running", com.ambientai.core.VoiceListeningService.isServiceRunning())
        put("timestamp", System.currentTimeMillis())
        put("app_version", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
    }.toString(2)
    private fun getLogs(count: Int): String {
        // This is a placeholder - actual implementation would need a log buffer
        return "Log streaming not yet implemented. Use: adb logcat -s VoiceService:D WorkflowExecutor:D DeepgramSTT:D"
    }
    private fun getWorkflows() = try {
        val workflows = workflowRepo.getEnabled()
        JSONArray().apply {
            workflows.forEach { workflow ->
                put(JSONObject().apply {
                    put("id", workflow.id)
                    put("name", workflow.name)
                    put("enabled", workflow.enabled)
                })
            }
        }.toString(2)
    } catch (e: Exception) {
        "ERROR: ${e.message}"
    }
    private fun triggerWorkflow(workflowName: String): String {
        return try {
            // Trigger via service
            val intent = Intent(context, com.ambientai.core.VoiceListeningService::class.java).apply {
                action = "TRIGGER_WORKFLOW"
                putExtra("workflow_name", workflowName)
            }
            context.startService(intent)
            "Triggered workflow: $workflowName"
        } catch (e: Exception) {
            "ERROR: ${e.message}"
        }
    }
    private fun getTranscripts(count: Int) = try {
        val transcripts = transcriptRepo.getAll().take(count)
        JSONArray().apply {
            transcripts.forEach { transcript ->
                put(JSONObject().apply {
                    put("id", transcript.id)
                    put("text", transcript.text)
                    put("timestamp", transcript.timestamp)
                    put("excludeFromContext", transcript.excludeFromContext)
                })
            }
        }.toString(2)
    } catch (e: Exception) {
        "ERROR: ${e.message}"
    }
    private fun getHelp() = """
        Available Commands:
        - ping                    : Test connection
        - status                  : Get service status
        - workflows               : List all workflows
        - trigger:<name>          : Trigger a workflow by name
        - transcripts:<count>     : Get recent transcripts (default 10)
        - logs:<count>            : Get recent logs (default 50)
        - help                    : Show this help

        Example:
        adb shell am broadcast -a com.ambientai.DEBUG_COMMAND -e cmd "status"
    """.trimIndent()
}
