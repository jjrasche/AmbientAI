package com.ambientai.workflow

import android.content.Context
import android.util.Log
import com.ambientai.data.entities.WorkflowDefinition
import com.ambientai.data.repositories.WorkflowRepository
import org.json.JSONObject

/**
 * Routes transcripts to workflows based on trigger phrase matching.
 * Uses exact phrase matching (case-insensitive) to find workflows.
 */
class WorkflowRouter(context: Context) {

    private val workflowRepo = WorkflowRepository(context)
    private var workflows: List<WorkflowDefinition> = emptyList()

    companion object {
        private const val TAG = "WorkflowRouter"
    }

    /**
     * Load enabled workflows from database.
     * Call this on initialization and when workflows change.
     */
    fun loadWorkflows() {
        workflows = workflowRepo.getEnabled()
        Log.d(TAG, "Loaded ${workflows.size} enabled workflows")
    }

    /**
     * Find the best workflow for this transcript.
     * Returns null if no workflow matches.
     * Returns WorkflowMatch if exactly one workflow matches.
     * Throws MultipleMatchException if multiple workflows match.
     *
     * @param transcript User's voice input
     * @return WorkflowMatch with initialized context, or null
     * @throws MultipleMatchException if multiple workflows match
     */
    fun route(transcript: String): WorkflowMatch? {
        if (workflows.isEmpty()) {
            Log.w(TAG, "No workflows loaded")
            return null
        }

        val lowerTranscript = transcript.lowercase()

        // Find all matching workflows
        val matches = workflows.mapNotNull { workflow ->
            val matchedTrigger = findMatchingTrigger(workflow, lowerTranscript)
            if (matchedTrigger != null) {
                WorkflowMatchCandidate(
                    definition = workflow,
                    matchedTrigger = matchedTrigger,
                    matchLength = matchedTrigger.length
                )
            } else {
                null
            }
        }

        return when {
            matches.isEmpty() -> {
                Log.d(TAG, "No workflow matched: $transcript")
                null
            }
            matches.size == 1 -> {
                val match = matches.first()
                Log.d(TAG, "Matched workflow '${match.definition.name}' via trigger '${match.matchedTrigger}'")
                createWorkflowMatch(match, transcript)
            }
            else -> {
                // Multiple workflows matched
                val workflowNames = matches.joinToString(", ") { it.definition.name }
                Log.w(TAG, "Multiple workflows matched: $workflowNames")
                throw MultipleMatchException(
                    transcript = transcript,
                    matchedWorkflows = matches.map { it.definition.name }
                )
            }
        }
    }

    /**
     * Find a trigger phrase in this workflow that matches the transcript.
     * Uses exact phrase matching (case-insensitive).
     *
     * @return Matched trigger phrase, or null if no match
     */
    private fun findMatchingTrigger(workflow: WorkflowDefinition, lowerTranscript: String): String? {
        val triggers = parseTriggers(workflow.definition)

        return triggers.firstOrNull { trigger ->
            lowerTranscript.contains(trigger.lowercase())
        }
    }

    /**
     * Parse trigger phrases from workflow JSON.
     * Expected format: { "triggers": ["phrase one", "phrase two"] }
     */
    private fun parseTriggers(workflowJson: String): List<String> {
        return try {
            val json = JSONObject(workflowJson)
            val triggersArray = json.getJSONArray("triggers")
            List(triggersArray.length()) { i ->
                triggersArray.getString(i)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse triggers from workflow JSON", e)
            emptyList()
        }
    }

    /**
     * Create WorkflowMatch with initialized execution context.
     */
    private fun createWorkflowMatch(
        candidate: WorkflowMatchCandidate,
        transcript: String
    ): WorkflowMatch {
        val context = WorkflowExecutionContext(
            workflowId = candidate.definition.id,
            workflowName = candidate.definition.name,
            transcript = transcript,
            matchedTrigger = candidate.matchedTrigger
        )

        // Initialize built-in variables
        context.variables["transcript"] = transcript

        return WorkflowMatch(
            definition = candidate.definition,
            context = context
        )
    }

    /**
     * Internal data class for tracking match candidates.
     */
    private data class WorkflowMatchCandidate(
        val definition: WorkflowDefinition,
        val matchedTrigger: String,
        val matchLength: Int
    )
}

/**
 * Exception thrown when multiple workflows match the same transcript.
 * Service should speak this error to the user.
 */
class MultipleMatchException(
    val transcript: String,
    val matchedWorkflows: List<String>
) : Exception("Multiple workflows matched: ${matchedWorkflows.joinToString(", ")}")