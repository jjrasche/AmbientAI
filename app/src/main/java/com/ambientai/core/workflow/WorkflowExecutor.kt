package com.ambientai.workflow

import android.content.Context
import android.util.Log
import com.ambientai.core.llm.GroqLlmService
import com.ambientai.core.tts.TextToSpeechService
import com.ambientai.data.entities.WorkflowExecutionLog
import com.ambientai.data.entities.ActionExecutionLog
import com.ambientai.data.repositories.TranscriptRepository
import com.ambientai.data.repositories.WorkflowExecutionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray

/**
 * Executes workflows by running their steps sequentially.
 * Handles variable substitution, action dispatch, and logging.
 */
class WorkflowExecutor(private val context: Context) {

    private val executionRepo = WorkflowExecutionRepository(context)
    private val transcriptRepo = TranscriptRepository(context)
    private val llmService = GroqLlmService()
    private var ttsService: TextToSpeechService? = null

    companion object {
        private const val TAG = "WorkflowExecutor"
    }

    /**
     * Initialize TTS service if not already initialized.
     */
    private suspend fun ensureTtsInitialized() {
        if (ttsService == null) {
            ttsService = TextToSpeechService(context)
            ttsService?.initialize()
        }
    }

    /**
     * Execute a workflow from start to finish.
     * Returns success/failure and logs everything to database.
     */
    suspend fun execute(match: WorkflowMatch): WorkflowResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val executionLog = WorkflowExecutionLog(
            workflowId = match.definition.id,
            workflowName = match.definition.name,
            transcript = match.context.transcript,
            matchedTrigger = match.context.matchedTrigger,
            success = false,
            executionTimeMs = 0,
            timestamp = startTime
        )

        // Save initial log to get ID for action logs
        executionRepo.save(executionLog)

        try {
            val workflowJson = JSONObject(match.definition.definition)
            val steps = workflowJson.getJSONArray("steps")

            // Execute each step sequentially
            for (i in 0 until steps.length()) {
                val step = steps.getJSONObject(i)
                executeStep(step, match.context, executionLog.id, i, "$i")
            }

            executionLog.success = true
            executionLog.executionTimeMs = System.currentTimeMillis() - startTime
            executionRepo.save(executionLog)

            WorkflowResult.Success

        } catch (e: Exception) {
            Log.e(TAG, "Workflow execution failed", e)
            executionLog.success = false
            executionLog.errorMessage = e.message
            executionLog.executionTimeMs = System.currentTimeMillis() - startTime
            executionRepo.save(executionLog)

            WorkflowResult.Failure(e.message ?: "Unknown error")
        }
    }

    /**
     * Execute a single step (action or control flow).
     */
    private suspend fun executeStep(
        step: JSONObject,
        context: WorkflowExecutionContext,
        executionId: Long,
        stepIndex: Int,
        stepPath: String
    ) {
        val actionName = step.getString("action")

        when {
            actionName == "control.if" -> executeConditional(step, context, executionId, stepIndex, stepPath)
            else -> executeAction(step, context, executionId, stepIndex, stepPath)
        }
    }

    /**
     * Execute a regular action by dispatching to appropriate service.
     */
    private suspend fun executeAction(
        step: JSONObject,
        context: WorkflowExecutionContext,
        executionId: Long,
        stepIndex: Int,
        stepPath: String
    ) {
        val actionName = step.getString("action")
        val inputJson = step.getJSONObject("input")
        val outputVar = step.optString("output", null)

        val startTime = System.currentTimeMillis()

        try {
            // 1. Resolve variables in input
            val resolvedInput = resolveVariables(inputJson, context)

            // 2. Dispatch to appropriate service based on action name
            val result = when (actionName) {
                "buffer.getRecentTranscript" -> {
                    val chunks = resolvedInput.optInt("chunks", 3)
                    transcriptRepo.getRecentContext(chunks)
                }

                "llm.prompt" -> {
                    val systemPrompt = resolvedInput.getString("system_prompt")
                    val userPrompt = resolvedInput.getString("user_prompt")
                    val temperature = resolvedInput.optDouble("temperature", 0.7).toFloat()
                    val maxTokens = resolvedInput.optInt("max_tokens", 256)

                    val llmResult = llmService.generateResponse(
                        systemPrompt = systemPrompt,
                        userPrompt = userPrompt,
                        temperature = temperature,
                        maxTokens = maxTokens
                    )
                    llmResult.getOrThrow()
                }

                "tts.speak" -> {
                    val text = resolvedInput.getString("text")
                    ensureTtsInitialized()
                    ttsService?.speak(text)
                    null // TTS has no return value
                }

                "json.parse" -> {
                    val text = resolvedInput.getString("text")
                    // TODO: Validate against schema if provided
                    JSONObject(text)
                }

                else -> throw IllegalArgumentException("Unknown action: $actionName")
            }

            val latency = System.currentTimeMillis() - startTime

            // 3. Store output in context
            if (outputVar != null && result != null) {
                context.variables[outputVar] = result
            }

            // 4. Log execution
            val actionLog = ActionExecutionLog(
                workflowExecutionId = executionId,
                stepIndex = stepIndex,
                stepPath = stepPath,
                actionName = actionName,
                inputJson = resolvedInput.toString(),
                outputJson = result?.toString() ?: "",
                success = true,
                latencyMs = latency,
                timestamp = System.currentTimeMillis()
            )
            executionRepo.saveAction(actionLog)

            Log.d(TAG, "Executed $actionName in ${latency}ms")

        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime

            // Log failed execution
            val actionLog = ActionExecutionLog(
                workflowExecutionId = executionId,
                stepIndex = stepIndex,
                stepPath = stepPath,
                actionName = actionName,
                inputJson = inputJson.toString(),
                outputJson = "",
                success = false,
                errorMessage = e.message,
                latencyMs = latency,
                timestamp = System.currentTimeMillis()
            )
            executionRepo.saveAction(actionLog)

            Log.e(TAG, "Action $actionName failed at step $stepPath", e)
            throw e
        }
    }

    /**
     * Execute if/else conditional.
     */
    private suspend fun executeConditional(
        step: JSONObject,
        context: WorkflowExecutionContext,
        executionId: Long,
        stepIndex: Int,
        stepPath: String
    ) {
        val condition = step.getString("condition")
        val thenSteps = step.getJSONArray("then")
        val elseSteps = step.optJSONArray("else")

        // Evaluate condition against context variables
        val conditionResult = evaluateCondition(condition, context)

        val branchSteps = if (conditionResult) thenSteps else elseSteps

        if (branchSteps != null) {
            val branchName = if (conditionResult) "then" else "else"
            for (i in 0 until branchSteps.length()) {
                val branchStep = branchSteps.getJSONObject(i)
                executeStep(branchStep, context, executionId, stepIndex, "$stepPath.$branchName.$i")
            }
        }
    }

    /**
     * Replace $variables in JSON input with actual values from context.
     */
    private fun resolveVariables(input: JSONObject, context: WorkflowExecutionContext): JSONObject {
        // TODO: Walk JSON tree, replace "$varName" strings with context.variables["varName"]
        // Handle {{NOW}}, {{UUID}} templates
        // For MVP, just return input as-is
        return input
    }

    /**
     * Evaluate condition expression against context variables.
     * Simple approach: string replacement + eval (for MVP).
     */
    private fun evaluateCondition(condition: String, context: WorkflowExecutionContext): Boolean {
        // TODO: Replace $varName with actual values
        // TODO: Eval as boolean expression
        // For MVP: just return true to test then branches
        return true
    }

    /**
     * Clean up resources.
     */
    fun cleanup() {
        ttsService?.cleanup()
    }
}

sealed class WorkflowResult {
    object Success : WorkflowResult()
    data class Failure(val error: String) : WorkflowResult()
}