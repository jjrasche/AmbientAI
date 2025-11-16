package com.ambientai.workflow

import android.util.Log
import com.ambientai.core.llm.GroqLlmService
import com.ambientai.data.entities.WorkflowDefinition
import com.ambientai.data.repositories.IWorkflowDefinitionRepository
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkflowRouter @Inject constructor(
    private val workflowRepo: IWorkflowDefinitionRepository,
    private val llmService: GroqLlmService
) {
    private var workflows: List<WorkflowDefinition> = emptyList()

    companion object {
        private const val TAG = "WorkflowRouter"
        private const val SHORT_TRANSCRIPT_THRESHOLD = 10
    }
    fun loadWorkflows() { workflows = workflowRepo.getEnabled() }
    fun route(transcript: String, transcriptId: Long, isPartial: Boolean = false): WorkflowMatch? = workflows.takeIf { it.isNotEmpty() }?.let {
        val wordCount = transcript.split("\\s+".toRegex()).size
        val lowerTranscript = transcript.lowercase()
        Log.d(TAG, "🔍 ROUTING: \"$transcript\" ($wordCount words)${if (isPartial) " [PARTIAL]" else ""}")
        val matches = workflows.mapNotNull { workflow -> if (checkConditions(workflow)) findMatchingTrigger(workflow, lowerTranscript)?.let { matchedTrigger -> WorkflowMatchCandidate(definition = workflow, matchedTrigger = matchedTrigger, matchLength = matchedTrigger.length) } else null }
        Log.d(TAG, "   ↳ Found ${matches.size} exact trigger matches")
        when {
            matches.isEmpty() && wordCount < SHORT_TRANSCRIPT_THRESHOLD && !isPartial -> {
                Log.d(TAG, "🧠 SHORT TRANSCRIPT ($wordCount words) - attempting LLM intent extraction")
                extractIntentWithLlm(transcript, transcriptId)
            }
            matches.isEmpty() -> createConversationalDefault(transcript, transcriptId)
            matches.size == 1 -> createWorkflowMatch(matches.first(), transcript, transcriptId)
            else -> throw MultipleMatchException(transcript = transcript, matchedWorkflows = matches.map { it.definition.name })
        }
    }
    private fun extractIntentWithLlm(transcript: String, transcriptId: Long): WorkflowMatch? {
        val workflowList = workflows.joinToString("\n") { workflow -> "- ${workflow.name}: ${parseTriggers(workflow.definition).joinToString(", ")}" }
        val systemPrompt = "You are a workflow intent extractor. Given a short user command, identify which workflow best matches their intent. Respond with ONLY the workflow name from the list, or 'NONE' if no match."
        val userPrompt = "User said: \"$transcript\"\n\nAvailable workflows:\n$workflowList\n\nWhich workflow matches this intent?"
        return runBlocking {
            try {
                val result = llmService.execute("llm.prompt", JSONObject().apply {
                    put("systemPrompt", systemPrompt)
                    put("userPrompt", userPrompt)
                    put("temperature", 0.3)
                    put("maxTokens", 50)
                })
                if (result.optBoolean("success", false)) {
                    val extractedWorkflow = result.optString("response", "").trim()
                    Log.d(TAG, "✓ LLM EXTRACTED INTENT: $extractedWorkflow")
                    workflows.find { it.name.equals(extractedWorkflow, ignoreCase = true) }?.let { workflow ->
                        createWorkflowMatch(WorkflowMatchCandidate(workflow, "(LLM extracted)", 0), transcript, transcriptId)
                    } ?: run {
                        Log.w(TAG, "⚠ LLM returned unknown workflow: $extractedWorkflow")
                        createConversationalDefault(transcript, transcriptId)
                    }
                } else {
                    Log.e(TAG, "✖ LLM INTENT EXTRACTION FAILED: ${result.optString("error")}")
                    createConversationalDefault(transcript, transcriptId)
                }
            } catch (e: Exception) {
                Log.e(TAG, "✖ LLM INTENT EXTRACTION ERROR: ${e.message}")
                createConversationalDefault(transcript, transcriptId)
            }
        }
    }
    private fun createConversationalDefault(transcript: String, transcriptId: Long) = WorkflowDefinition(id = -1, name = "conversational_default", enabled = true, definition = """{"triggers":{"keywords":[]},"steps":[{"action":"llm.prompt","input":{"systemPrompt":"You are a helpful voice assistant. Provide brief, conversational responses.","userPrompt":"$transcript","temperature":0.7,"maxTokens":50},"output":"response"},{"action":"tts.speak","input":{"text":"${'$'}response.response"}}]}""").let { defaultWorkflow -> WorkflowExecutionContext(workflowId = -1, workflowName = "conversational_default", transcript = transcript, matchedTrigger = "(default)").apply { variables["transcript"] = transcript; variables["transcriptId"] = transcriptId }.let { context -> WorkflowMatch(defaultWorkflow, context) } }
    private fun findMatchingTrigger(workflow: WorkflowDefinition, lowerTranscript: String): String? = parseTriggers(workflow.definition).firstOrNull { trigger -> lowerTranscript.contains(trigger.lowercase()) }
    private fun parseTriggers(workflowJson: String) = runCatching { JSONObject(workflowJson).let { json -> json.optJSONObject("triggers")?.let { triggersObj -> triggersObj.optJSONArray("keywords")?.let { keywordsArray -> List(keywordsArray.length()) { i -> keywordsArray.getString(i) } } ?: emptyList() } ?: json.optJSONArray("triggers")?.let { triggersArray -> List(triggersArray.length()) { i -> triggersArray.getString(i) } } ?: emptyList() } }.getOrElse { emptyList() }
    private fun checkConditions(workflow: WorkflowDefinition): Boolean = true
    private fun createWorkflowMatch(candidate: WorkflowMatchCandidate, transcript: String, transcriptId: Long): WorkflowMatch {
        val cleanedTranscript = transcript.replace(Regex("\\b(play|music|song|track)\\b", RegexOption.IGNORE_CASE), "").trim()
        Log.d(TAG, "   ↳ QUERY CLEANING: \"$transcript\" → \"$cleanedTranscript\"")
        return WorkflowExecutionContext(workflowId = candidate.definition.id, workflowName = candidate.definition.name, transcript = transcript, matchedTrigger = candidate.matchedTrigger).apply { variables["transcript"] = cleanedTranscript; variables["transcriptId"] = transcriptId }.let { context -> WorkflowMatch(definition = candidate.definition, context = context) }
    }
    private data class WorkflowMatchCandidate(val definition: WorkflowDefinition, val matchedTrigger: String, val matchLength: Int)
}
class MultipleMatchException(val transcript: String, val matchedWorkflows: List<String>) : Exception("Multiple workflows matched: ${matchedWorkflows.joinToString(", ")}")
