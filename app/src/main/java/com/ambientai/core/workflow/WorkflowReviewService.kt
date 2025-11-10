package com.ambientai.core.workflow

import com.ambientai.data.entities.ActionExecution
import com.ambientai.data.entities.WorkflowDefinition
import com.ambientai.data.entities.WorkflowExecution
import com.ambientai.data.repositories.ActionExecutionRepository
import com.ambientai.data.repositories.WorkflowDefinitionRepository
import com.ambientai.data.repositories.WorkflowExecutionRepository
import com.ambientai.services.llm.LlmService
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkflowReviewService @Inject constructor(
    private val llmService: LlmService,
    private val workflowDefinitionRepository: WorkflowDefinitionRepository,
    private val workflowExecutionRepository: WorkflowExecutionRepository,
    private val actionExecutionRepository: ActionExecutionRepository
) {
    suspend fun generateSuggestions(workflow: WorkflowDefinition, executions: List<WorkflowExecution>): ReviewSuggestions {
        val actions = executions.flatMap { workflowExecutionRepository.getActionsForExecution(it.id) }
        val llmActions = actions.filter { it.actionName.contains("llm") }
        val failedExecutions = executions.filter { !it.success }
        val failedActions = actions.filter { !it.success }
        val analysisPrompt = buildAnalysisPrompt(workflow, executions, actions, llmActions, failedExecutions, failedActions)
        val response = llmService.generateResponse("You are analyzing workflow execution data to suggest improvements. Return ONLY valid JSON, no markdown fences.", analysisPrompt)
        return parseReviewSuggestions(response)
    }
    private fun buildAnalysisPrompt(workflow: WorkflowDefinition, executions: List<WorkflowExecution>, actions: List<ActionExecution>, llmActions: List<ActionExecution>, failedExecutions: List<WorkflowExecution>, failedActions: List<ActionExecution>): String = """
Analyze the following workflow execution data and suggest improvements.

WORKFLOW: ${workflow.name}
Status: ${if (workflow.enabled) "ENABLED" else "DISABLED"}
Definition:
${workflow.definition}

REVIEW NOTES:
${workflow.reviewNotes.ifEmpty { "None" }}

EXECUTION SUMMARY:
- Total executions: ${executions.size}
- Successful: ${executions.count { it.success }}
- Failed: ${failedExecutions.size}
- LLM actions: ${llmActions.size}
- Graded LLM actions: ${llmActions.count { it.grade != null }}
- Average LLM grade: ${llmActions.mapNotNull { it.grade }.average().takeIf { !it.isNaN() } ?: "N/A"}

RECENT EXECUTIONS:
${executions.take(10).joinToString("\n") {
    "- Transcript: '${it.transcript}' | Trigger: '${it.matchedTrigger}' | ${if (it.success) "SUCCESS" else "FAILED: ${it.errorMessage}"} | ${it.executionTimeMs}ms"
}}

FAILED EXECUTIONS:
${failedExecutions.joinToString("\n") {
    "- Transcript: '${it.transcript}' | Error: ${it.errorMessage}"
}}

FAILED ACTIONS:
${failedActions.joinToString("\n") {
    "- Action: ${it.actionName} | Error: ${it.errorMessage}"
}}

LLM ACTION GRADES:
${llmActions.filter { it.grade != null }.joinToString("\n") {
    "- Grade: ${it.grade}/5 | Input: ${JSONObject(it.inputJson).optString("userPrompt", "").take(100)} | Output: ${JSONObject(it.outputJson).optString("response", "").take(100)}"
}}

Based on this data and the review notes, suggest improvements in the following JSON format:
{
  "workflow_refinements": [
    {
      "type": "trigger_add" | "trigger_remove" | "prompt_update" | "tts_update",
      "path": "triggers" | "steps.N.input.system_prompt" | "steps.N.input.text",
      "value": "new value for adds/updates",
      "old_value": "existing value for updates/removes",
      "rationale": "why this change improves the workflow"
    }
  ],
  "workflow_expansions": [
    {
      "name": "suggested_workflow_name",
      "triggers": ["phrase one", "phrase two"],
      "rationale": "why this new workflow is needed"
    }
  ],
  "llm_grading_insights": [
    {
      "action_id": 123,
      "suggested_grade": 4,
      "notes": "why this grade based on user's grading patterns"
    }
  ]
}

Return ONLY the JSON, no additional text or markdown fences.
""".trimIndent()
    private fun parseReviewSuggestions(jsonResponse: String): ReviewSuggestions = try {
        val json = JSONObject(jsonResponse.removePrefix("```json").removeSuffix("```").trim())
        ReviewSuggestions(
            workflowRefinements = json.optJSONArray("workflow_refinements")?.let { parseRefinements(it) } ?: emptyList(),
            workflowExpansions = json.optJSONArray("workflow_expansions")?.let { parseExpansions(it) } ?: emptyList(),
            llmGradingInsights = json.optJSONArray("llm_grading_insights")?.let { parseGradingInsights(it) } ?: emptyList()
        )
    } catch (e: Exception) {
        ReviewSuggestions(emptyList(), emptyList(), emptyList())
    }
    private fun parseRefinements(array: JSONArray) = (0 until array.length()).map { i ->
        val obj = array.getJSONObject(i)
        WorkflowRefinement(obj.optString("type"), obj.optString("path"), obj.optString("value"), obj.optString("old_value"), obj.optString("rationale"))
    }
    private fun parseExpansions(array: JSONArray) = (0 until array.length()).map { i ->
        val obj = array.getJSONObject(i)
        val triggers = obj.optJSONArray("triggers")?.let { (0 until it.length()).map { j -> it.getString(j) } } ?: emptyList()
        WorkflowExpansion(obj.optString("name"), triggers, obj.optString("rationale"))
    }
    private fun parseGradingInsights(array: JSONArray) = (0 until array.length()).map { i ->
        val obj = array.getJSONObject(i)
        LlmGradingInsight(obj.optLong("action_id"), obj.optInt("suggested_grade"), obj.optString("notes"))
    }
}
data class ReviewSuggestions(val workflowRefinements: List<WorkflowRefinement>, val workflowExpansions: List<WorkflowExpansion>, val llmGradingInsights: List<LlmGradingInsight>)
data class WorkflowRefinement(val type: String, val path: String, val value: String, val oldValue: String, val rationale: String)
data class WorkflowExpansion(val name: String, val triggers: List<String>, val rationale: String)
data class LlmGradingInsight(val actionId: Long, val suggestedGrade: Int, val notes: String)
