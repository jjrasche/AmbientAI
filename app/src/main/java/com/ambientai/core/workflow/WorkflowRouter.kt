package com.ambientai.workflow

import android.util.Log
import com.ambientai.data.entities.WorkflowDefinition
import com.ambientai.data.repositories.WorkflowDefinitionRepository
import org.json.JSONObject

class WorkflowRouter() {
    private val workflowRepo = WorkflowDefinitionRepository()
    private var workflows: List<WorkflowDefinition> = emptyList()

    fun loadWorkflows() {
        workflows = workflowRepo.getEnabled()
    }

    fun route(transcript: String, transcriptId: Long): WorkflowMatch? {
        if (workflows.isEmpty()) return null
        val lowerTranscript = transcript.lowercase()
        val matches = workflows.mapNotNull { workflow ->
            findMatchingTrigger(workflow, lowerTranscript)?.let { matchedTrigger ->
                WorkflowMatchCandidate(definition = workflow, matchedTrigger = matchedTrigger, matchLength = matchedTrigger.length)
            }
        }
        return when {
            matches.isEmpty() -> createConversationalDefault(transcript, transcriptId)
            matches.size == 1 -> createWorkflowMatch(matches.first(), transcript, transcriptId)
            else -> throw MultipleMatchException(transcript = transcript, matchedWorkflows = matches.map { it.definition.name })
        }
    }


    private fun createConversationalDefault(transcript: String, transcriptId: Long): WorkflowMatch {
        val defaultWorkflow = WorkflowDefinition(id = -1, name = "conversational_default", enabled = true, definition = """{"triggers":[],"steps":[{"action":"llm.prompt","input":{"systemPrompt":"You are a helpful voice assistant. Provide brief, conversational responses.","userPrompt":"$transcript","temperature":0.7,"maxTokens":50},"output":"response"},{"action":"tts.speak","input":{"text":"${'$'}response.response"}}]}""")
        val context = WorkflowExecutionContext(workflowId = -1, workflowName = "conversational_default", transcript = transcript, matchedTrigger = "(default)")
        context.variables["transcript"] = transcript
        context.variables["transcriptId"] = transcriptId
        return WorkflowMatch(defaultWorkflow, context)
    }

    private fun findMatchingTrigger(workflow: WorkflowDefinition, lowerTranscript: String): String? =
        parseTriggers(workflow.definition).firstOrNull { trigger -> lowerTranscript.contains(trigger.lowercase()) }
    private fun parseTriggers(workflowJson: String): List<String> = try {
        val triggersArray = JSONObject(workflowJson).getJSONArray("triggers")
        List(triggersArray.length()) { i -> triggersArray.getString(i) }
    } catch (e: Exception) {
        Log.e("WorkflowRouter", "Failed to parse triggers from workflow JSON", e)
        emptyList()
    }
    private fun createWorkflowMatch(candidate: WorkflowMatchCandidate, transcript: String, transcriptId: Long): WorkflowMatch {
        val context = WorkflowExecutionContext(workflowId = candidate.definition.id, workflowName = candidate.definition.name, transcript = transcript, matchedTrigger = candidate.matchedTrigger)
        context.variables["transcript"] = transcript
        context.variables["transcriptId"] = transcriptId
        return WorkflowMatch(definition = candidate.definition, context = context)
    }
    private data class WorkflowMatchCandidate(val definition: WorkflowDefinition, val matchedTrigger: String, val matchLength: Int)
}

class MultipleMatchException(val transcript: String, val matchedWorkflows: List<String>) : Exception("Multiple workflows matched: ${matchedWorkflows.joinToString(", ")}")