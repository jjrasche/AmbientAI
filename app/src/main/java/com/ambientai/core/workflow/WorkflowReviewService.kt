package com.ambientai.core.workflow

import com.ambientai.data.entities.WorkflowDefinition
import com.ambientai.data.entities.WorkflowExecution
import com.ambientai.data.repositories.IWorkflowDefinitionRepository
import com.ambientai.workflow.WorkflowExecutor
import com.ambientai.workflow.WorkflowResult
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkflowReviewService @Inject constructor(
    private val workflowExecutor: WorkflowExecutor,
    private val workflowRepo: IWorkflowDefinitionRepository
) {
    suspend fun generateSuggestions(workflow: WorkflowDefinition, executions: List<WorkflowExecution>): ReviewSuggestions {
        val reviewWorkflow = workflowRepo.getByName("review_workflow") ?: throw IllegalStateException("review_workflow not found in database. Run workflow seeder.")
        val result = workflowExecutor.executeById(reviewWorkflow.id, mapOf("context" to mapOf("targetWorkflowId" to workflow.id)))
        return when (result) {
            is WorkflowResult.Success -> {
                val suggestionsJson = (result.variables["suggestions"] as? JSONObject)?.optString("response") ?: throw IllegalStateException("review_workflow did not produce 'suggestions' output")
                parseReviewSuggestions(suggestionsJson)
            }
            is WorkflowResult.Failure -> throw IllegalStateException("Review workflow failed: ${result.error}")
        }
    }
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
