package com.ambientai.workflow

import com.ambientai.core.music.MusicPlayerService
import com.ambientai.data.entities.WorkflowDefinition
import com.ambientai.data.repositories.IWorkflowDefinitionRepository
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkflowRouter @Inject constructor(
    private val workflowRepo: IWorkflowDefinitionRepository,
    private val musicPlayer: MusicPlayerService
) {
    private var workflows: List<WorkflowDefinition> = emptyList()

    fun loadWorkflows() { workflows = workflowRepo.getEnabled() }
    fun route(transcript: String, transcriptId: Long): WorkflowMatch? = workflows.takeIf { it.isNotEmpty() }?.let { transcript.lowercase().let { lowerTranscript -> workflows.mapNotNull { workflow -> if (checkConditions(workflow)) findMatchingTrigger(workflow, lowerTranscript)?.let { matchedTrigger -> WorkflowMatchCandidate(definition = workflow, matchedTrigger = matchedTrigger, matchLength = matchedTrigger.length) } else null }.let { matches -> when { matches.isEmpty() -> createConversationalDefault(transcript, transcriptId); matches.size == 1 -> createWorkflowMatch(matches.first(), transcript, transcriptId); else -> throw MultipleMatchException(transcript = transcript, matchedWorkflows = matches.map { it.definition.name }) } } } }
    private fun createConversationalDefault(transcript: String, transcriptId: Long) = WorkflowDefinition(id = -1, name = "conversational_default", enabled = true, definition = """{"triggers":{"keywords":[]},"steps":[{"action":"llm.prompt","input":{"systemPrompt":"You are a helpful voice assistant. Provide brief, conversational responses.","userPrompt":"$transcript","temperature":0.7,"maxTokens":50},"output":"response"},{"action":"tts.speak","input":{"text":"${'$'}response.response"}}]}""").let { defaultWorkflow -> WorkflowExecutionContext(workflowId = -1, workflowName = "conversational_default", transcript = transcript, matchedTrigger = "(default)").apply { variables["transcript"] = transcript; variables["transcriptId"] = transcriptId }.let { context -> WorkflowMatch(defaultWorkflow, context) } }
    private fun findMatchingTrigger(workflow: WorkflowDefinition, lowerTranscript: String): String? = parseTriggers(workflow.definition).firstOrNull { trigger -> lowerTranscript.contains(trigger.lowercase()) }
    private fun parseTriggers(workflowJson: String) = runCatching { JSONObject(workflowJson).let { json -> json.optJSONObject("triggers")?.let { triggersObj -> triggersObj.optJSONArray("keywords")?.let { keywordsArray -> List(keywordsArray.length()) { i -> keywordsArray.getString(i) } } ?: emptyList() } ?: json.optJSONArray("triggers")?.let { triggersArray -> List(triggersArray.length()) { i -> triggersArray.getString(i) } } ?: emptyList() } }.getOrElse { emptyList() }
    private fun checkConditions(workflow: WorkflowDefinition): Boolean = runCatching { JSONObject(workflow.definition).optJSONObject("triggers")?.optJSONObject("conditions")?.let { conditions -> if (conditions.has("playbackActive")) { val requiredPlaying = conditions.getBoolean("playbackActive"); if (requiredPlaying) musicPlayer.isPlaying() else !musicPlayer.isPlaying() } else true } ?: true }.getOrElse { true }
    private fun createWorkflowMatch(candidate: WorkflowMatchCandidate, transcript: String, transcriptId: Long) = WorkflowExecutionContext(workflowId = candidate.definition.id, workflowName = candidate.definition.name, transcript = transcript, matchedTrigger = candidate.matchedTrigger).apply { variables["transcript"] = transcript; variables["transcriptId"] = transcriptId }.let { context -> WorkflowMatch(definition = candidate.definition, context = context) }
    private data class WorkflowMatchCandidate(val definition: WorkflowDefinition, val matchedTrigger: String, val matchLength: Int)
}
class MultipleMatchException(val transcript: String, val matchedWorkflows: List<String>) : Exception("Multiple workflows matched: ${matchedWorkflows.joinToString(", ")}")
