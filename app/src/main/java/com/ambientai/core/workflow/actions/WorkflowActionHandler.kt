package com.ambientai.core.workflow.actions

import com.ambientai.data.repositories.IActionExecutionRepository
import com.ambientai.data.repositories.IWorkflowDefinitionRepository
import com.ambientai.data.repositories.IWorkflowExecutionRepository
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkflowActionHandler @Inject constructor(
    private val workflowRepo: IWorkflowDefinitionRepository,
    private val executionRepo: IWorkflowExecutionRepository,
    private val actionRepo: IActionExecutionRepository
) {
    suspend fun execute(action: String, input: JSONObject): JSONObject? = when (action) {
        "workflow.getExecutionData" -> getExecutionData(input)
        else -> throw IllegalArgumentException("Unknown workflow action: $action")
    }
    private fun getExecutionData(input: JSONObject): JSONObject {
        val workflowId = input.getLong("workflowId")
        val limit = input.optInt("limit", 100)
        val workflow = workflowRepo.getById(workflowId) ?: throw IllegalArgumentException("Workflow $workflowId not found")
        val executions = executionRepo.getByWorkflowId(workflowId).take(limit)
        val allActions = executions.flatMap { executionRepo.getActionsForExecution(it.id) }
        val llmActions = allActions.filter { it.actionName.contains("llm") }
        val failedExecutions = executions.filter { !it.success }
        val failedActions = allActions.filter { !it.success }
        val gradedActions = llmActions.filter { it.grade != null }
        val avgGrade = gradedActions.mapNotNull { it.grade }.average().takeIf { !it.isNaN() }
        return JSONObject().apply {
            put("workflow", JSONObject().apply {
                put("id", workflow.id)
                put("name", workflow.name)
                put("definition", workflow.definition)
                put("enabled", workflow.enabled)
                put("reviewNotes", workflow.reviewNotes)
            })
            put("summary", JSONObject().apply {
                put("totalExecutions", executions.size)
                put("successfulExecutions", executions.count { it.success })
                put("failedExecutions", failedExecutions.size)
                put("llmActions", llmActions.size)
                put("gradedLlmActions", gradedActions.size)
                put("avgLlmGrade", avgGrade)
            })
            put("recentExecutions", JSONArray().apply {
                executions.take(10).forEach { exec ->
                    put(JSONObject().apply {
                        put("id", exec.id)
                        put("transcript", exec.transcript)
                        put("matchedTrigger", exec.matchedTrigger)
                        put("success", exec.success)
                        put("errorMessage", exec.errorMessage ?: "")
                        put("executionTimeMs", exec.executionTimeMs)
                        put("timestamp", exec.timestamp)
                    })
                }
            })
            put("failedExecutions", JSONArray().apply {
                failedExecutions.forEach { exec ->
                    put(JSONObject().apply {
                        put("transcript", exec.transcript)
                        put("errorMessage", exec.errorMessage ?: "")
                    })
                }
            })
            put("failedActions", JSONArray().apply {
                failedActions.forEach { action ->
                    put(JSONObject().apply {
                        put("actionName", action.actionName)
                        put("errorMessage", action.errorMessage ?: "")
                        put("inputJson", action.inputJson)
                    })
                }
            })
            put("gradedActions", JSONArray().apply {
                gradedActions.forEach { action ->
                    put(JSONObject().apply {
                        put("grade", action.grade)
                        put("inputJson", action.inputJson)
                        put("outputJson", action.outputJson)
                    })
                }
            })
        }
    }
}
