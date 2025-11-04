package com.ambientai.workflow

import android.content.Context
import android.util.Log
import com.ambientai.data.entities.WorkflowDefinition
import com.ambientai.data.repositories.WorkflowDefinitionRepository
import org.json.JSONObject

/**
 * Routes transcripts to workflows based on trigger phrase matching.
 * Uses exact phrase matching (case-insensitive) to find workflows.
 */
class WorkflowRouter() {

    private val workflowRepo = WorkflowDefinitionRepository()
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
     * @param transcriptId ID of the saved transcript
     * @return WorkflowMatch with initialized context, or null
     * @throws MultipleMatchException if multiple workflows match
     */
    fun route(transcript: String, transcriptId: Long): WorkflowMatch? {
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
                Log.d(TAG, "No workflow matched, using conversational default")
                createConversationalDefault(transcript, transcriptId)
            }
            matches.size == 1 -> {
                val match = matches.first()
                Log.d(TAG, "Matched workflow '${match.definition.name}' via trigger '${match.matchedTrigger}'")
                createWorkflowMatch(match, transcript, transcriptId)
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


    private fun createConversationalDefault(transcript: String, transcriptId: Long): WorkflowMatch {
        // Create an inline workflow definition for conversational response
        val defaultWorkflow = WorkflowDefinition(
            id = -1, // Synthetic ID
            name = "conversational_default",
            enabled = true,
            definition = """{
            "triggers":[],
            "steps":[
                {"action":"llm.prompt","input":{
                    "systemPrompt":"You are a helpful voice assistant. Provide brief, conversational responses.",
                    "userPrompt":"$transcript",
                    "temperature":0.7,
                    "maxTokens":150
                },"output":"response"},
                {"action":"tts.speak","input":{"text":"${'$'}response.response"}}
            ]
        }"""
        )

        val context = WorkflowExecutionContext(
            workflowId = -1,
            workflowName = "conversational_default",
            transcript = transcript,
            matchedTrigger = "(default)"
        )
        context.variables["transcript"] = transcript
        context.variables["transcriptId"] = transcriptId

        return WorkflowMatch(defaultWorkflow, context)
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
        transcript: String,
        transcriptId: Long
    ): WorkflowMatch {
        val context = WorkflowExecutionContext(
            workflowId = candidate.definition.id,
            workflowName = candidate.definition.name,
            transcript = transcript,
            matchedTrigger = candidate.matchedTrigger
        )

        // Initialize built-in variables
        context.variables["transcript"] = transcript
        context.variables["transcriptId"] = transcriptId

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