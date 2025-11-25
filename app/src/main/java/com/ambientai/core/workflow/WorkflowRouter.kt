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
    private val llmService: GroqLlmService,
    private val playbackStateManager: com.ambientai.core.music.PlaybackStateManager
) {
    private var workflows: List<WorkflowDefinition> = emptyList()

    companion object {
        private const val TAG = "WorkflowRouter"
        private const val SHORT_TRANSCRIPT_THRESHOLD = 10
    }
    fun loadWorkflows() { workflows = workflowRepo.getEnabled() }

    private data class PositionedMatch(
        val workflow: WorkflowDefinition,
        val trigger: String,
        val position: Int,
        val length: Int
    )

    /**
     * Route a transcript to workflows, finding all non-overlapping trigger matches.
     * Returns list of WorkflowMatch in order of trigger position.
     */
    fun route(transcript: String, transcriptId: Long, isPartial: Boolean = false): List<WorkflowMatch> {
        if (workflows.isEmpty()) return emptyList()

        val wordCount = transcript.split("\\s+".toRegex()).size
        val lowerTranscript = transcript.lowercase()
        Log.d(TAG, "🔍 ROUTING: \"$transcript\" ($wordCount words)${if (isPartial) " [PARTIAL]" else ""}")

        // Find all trigger matches with their positions (conditions checked at execution time)
        val allMatches = mutableListOf<PositionedMatch>()
        workflows.forEach { workflow ->
            parseTriggers(workflow.definition).forEach { trigger ->
                val triggerLower = trigger.lowercase()
                var searchStart = 0
                while (true) {
                    val position = lowerTranscript.indexOf(triggerLower, searchStart)
                    if (position == -1) break
                    allMatches.add(PositionedMatch(workflow, trigger, position, trigger.length))
                    searchStart = position + 1
                }
            }
        }

        // If no matches, try LLM extraction for short transcripts
        if (allMatches.isEmpty()) {
            Log.d(TAG, "   ↳ No trigger matches found")
            return if (wordCount < SHORT_TRANSCRIPT_THRESHOLD && !isPartial) {
                Log.d(TAG, "🧠 SHORT TRANSCRIPT ($wordCount words) - attempting LLM intent extraction")
                extractIntentWithLlm(transcript, transcriptId)?.let { listOf(it) } ?: emptyList()
            } else {
                emptyList()
            }
        }

        // Sort by position, then by trigger length (prefer longer matches)
        allMatches.sortWith(compareBy({ it.position }, { -it.length }))

        // Remove overlapping matches (keep longest at each position range)
        val selectedMatches = mutableListOf<PositionedMatch>()
        var lastEndPosition = -1

        allMatches.forEach { match ->
            if (match.position >= lastEndPosition) {
                val existing = selectedMatches.lastOrNull()
                if (existing != null && existing.position == match.position && match.length > existing.length) {
                    selectedMatches.removeAt(selectedMatches.lastIndex)
                    selectedMatches.add(match)
                    lastEndPosition = match.position + match.length
                } else if (existing == null || existing.position != match.position) {
                    selectedMatches.add(match)
                    lastEndPosition = match.position + match.length
                }
            }
        }

        Log.d(TAG, "   ↳ Found ${selectedMatches.size} workflow(s): ${selectedMatches.map { "${it.workflow.name}@${it.position}" }}")

        // Create WorkflowMatch for each, with appropriate transcript segment
        return selectedMatches.mapIndexed { index, match ->
            val segmentStart = match.position
            val segmentEnd = if (index < selectedMatches.size - 1) selectedMatches[index + 1].position else transcript.length
            val segment = transcript.substring(segmentStart, segmentEnd).trim()
                .replace(Regex("\\b(no wait|wait no|actually|never mind)\\b", RegexOption.IGNORE_CASE), "")
                .trim()
            createWorkflowMatch(WorkflowMatchCandidate(match.workflow, match.trigger, match.length), segment, transcriptId, match.position, match.position + match.length)
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
                        null
                    }
                } else {
                    Log.e(TAG, "✖ LLM INTENT EXTRACTION FAILED: ${result.optString("error")}")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "✖ LLM INTENT EXTRACTION ERROR: ${e.message}")
                null
            }
        }
    }
    private fun findMatchingTrigger(workflow: WorkflowDefinition, lowerTranscript: String): String? = parseTriggers(workflow.definition).firstOrNull { trigger -> lowerTranscript.contains(trigger.lowercase()) }
    private fun parseTriggers(workflowJson: String) = runCatching { JSONObject(workflowJson).let { json -> json.optJSONObject("triggers")?.let { triggersObj -> triggersObj.optJSONArray("keywords")?.let { keywordsArray -> List(keywordsArray.length()) { i -> keywordsArray.getString(i) } } ?: emptyList() } ?: json.optJSONArray("triggers")?.let { triggersArray -> List(triggersArray.length()) { i -> triggersArray.getString(i) } } ?: emptyList() } }.getOrElse { emptyList() }
    fun checkConditions(workflow: WorkflowDefinition): Boolean = runCatching {
        JSONObject(workflow.definition).optJSONObject("triggers")?.optJSONObject("conditions")?.let { conditions ->
            conditions.keys().asSequence().all { key ->
                when (key) {
                    "playbackActive" -> {
                        val expectedState = conditions.getBoolean(key)
                        val actualState = playbackStateManager.isPlaying()
                        Log.d(TAG, "   ↳ Condition check: playbackActive (expected=$expectedState, actual=$actualState)")
                        expectedState == actualState
                    }
                    else -> {
                        Log.w(TAG, "   ↳ Unknown condition: $key")
                        true
                    }
                }
            }
        } ?: true
    }.getOrElse { e ->
        Log.e(TAG, "   ↳ Error checking conditions: ${e.message}")
        true
    }
    private fun createWorkflowMatch(candidate: WorkflowMatchCandidate, transcript: String, transcriptId: Long, triggerStart: Int = 0, triggerEnd: Int = 0): WorkflowMatch = WorkflowExecutionContext(workflowId = candidate.definition.id, workflowName = candidate.definition.name, transcript = transcript, matchedTrigger = candidate.matchedTrigger).apply { variables["transcript"] = transcript; variables["transcriptId"] = transcriptId }.let { context -> WorkflowMatch(definition = candidate.definition, context = context, triggerStart = triggerStart, triggerEnd = triggerEnd) }
    private data class WorkflowMatchCandidate(val definition: WorkflowDefinition, val matchedTrigger: String, val matchLength: Int)
}
class MultipleMatchException(val transcript: String, val matchedWorkflows: List<String>) : Exception("Multiple workflows matched: ${matchedWorkflows.joinToString(", ")}")
