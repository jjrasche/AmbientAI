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
     * Supports nested access: $var.nested.path
     * Throws MissingVariableException if variable doesn't exist.
     */
    private fun resolveVariables(input: JSONObject, context: WorkflowExecutionContext): JSONObject {
        val result = JSONObject()

        input.keys().forEach { key ->
            val value = input.get(key)
            result.put(key, resolveValue(value, context))
        }

        return result
    }

    private fun resolveValue(value: Any, context: WorkflowExecutionContext): Any {
        return when (value) {
            is String -> resolveString(value, context)
            is JSONObject -> resolveVariables(value, context)
            is JSONArray -> resolveArray(value, context)
            else -> value
        }
    }

    private fun resolveArray(array: JSONArray, context: WorkflowExecutionContext): JSONArray {
        val result = JSONArray()
        for (i in 0 until array.length()) {
            result.put(resolveValue(array.get(i), context))
        }
        return result
    }

    private fun resolveString(str: String, context: WorkflowExecutionContext): Any {
        // Find all $variable or $variable.path.to.thing patterns
        val pattern = Regex("""\$([a-zA-Z_][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)*)""")

        val matches = pattern.findAll(str).toList()

        if (matches.isEmpty()) return str

        // If entire string is a single variable reference, return the actual value (preserve type)
        if (matches.size == 1 && matches[0].value == str) {
            val path = matches[0].groupValues[1]
            return resolveVariablePath(path, context)
        }

        // Otherwise, do string interpolation
        var result = str
        matches.forEach { match ->
            val path = match.groupValues[1]
            val value = resolveVariablePath(path, context)
            result = result.replace(match.value, value.toString())
        }

        return result
    }

    private fun resolveVariablePath(path: String, context: WorkflowExecutionContext): Any {
        val parts = path.split(".")
        var current: Any = context.variables[parts[0]]
            ?: throw MissingVariableException(parts[0])

        for (i in 1 until parts.size) {
            val part = parts[i]
            current = when (current) {
                is Map<*, *> -> current[part]
                    ?: throw MissingVariableException("${parts.subList(0, i+1).joinToString(".")}")
                is List<*> -> {
                    val index = part.toIntOrNull()
                        ?: throw IllegalArgumentException("Invalid list index: $part")
                    current.getOrNull(index)
                        ?: throw MissingVariableException("${parts.subList(0, i+1).joinToString(".")} (index out of bounds)")
                }
                else -> throw IllegalArgumentException("Cannot access property '$part' on ${current::class.simpleName}")
            }
        }

        return current
    }

    class MissingVariableException(varName: String) : Exception("Variable not found: \$$varName")

    /**
     * Evaluate condition expression against context variables.
     * Simple approach: string replacement + eval (for MVP).
     */
    /**
     * Evaluate condition expression against context variables.
     * Supports: ===, !==, ==, !=, >, <, >=, <=, &&, ||, !
     * Example: "$error === null && $data.name !== null"
     */
    private fun evaluateCondition(condition: String, context: WorkflowExecutionContext): Boolean {
        // First, resolve all $variables in the condition string
        val resolvedCondition = resolveVariablesInCondition(condition, context)

        // Parse and evaluate
        return evaluateExpression(resolvedCondition)
    }

    /**
     * Replace $variables with their string representations for evaluation.
     * Special handling for null, booleans, numbers, and strings.
     */
    private fun resolveVariablesInCondition(condition: String, context: WorkflowExecutionContext): String {
        val pattern = Regex("""\$([a-zA-Z_][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)*)""")

        var result = condition
        pattern.findAll(condition).forEach { match ->
            val path = match.groupValues[1]
            try {
                val value = resolveVariablePath(path, context)
                val replacement = when (value) {
                    null -> "null"
                    is String -> "\"${value.replace("\"", "\\\"")}\"" // Escape quotes
                    is Boolean -> value.toString()
                    is Number -> value.toString()
                    else -> "\"$value\"" // Stringify everything else
                }
                result = result.replace(match.value, replacement)
            } catch (e: MissingVariableException) {
                throw e
            }
        }

        return result
    }

    /**
     * Evaluate boolean expression with operator precedence:
     * 1. ! (not)
     * 2. Comparisons (===, !==, ==, !=, >, <, >=, <=)
     * 3. && (and)
     * 4. || (or)
     */
    private fun evaluateExpression(expr: String): Boolean {
        return evaluateOr(expr.trim())
    }

    private fun evaluateOr(expr: String): Boolean {
        val parts = splitByOperator(expr, "||")
        if (parts.size == 1) return evaluateAnd(parts[0])

        return parts.any { evaluateAnd(it) }
    }

    private fun evaluateAnd(expr: String): Boolean {
        val parts = splitByOperator(expr, "&&")
        if (parts.size == 1) return evaluateComparison(parts[0])

        return parts.all { evaluateComparison(it) }
    }

    private fun evaluateComparison(expr: String): Boolean {
        val trimmed = expr.trim()

        // Handle negation
        if (trimmed.startsWith("!")) {
            return !evaluateComparison(trimmed.substring(1))
        }

        // Try each comparison operator in order (longest first to match === before ==)
        val operators = listOf("===", "!==", "==", "!=", ">=", "<=", ">", "<")

        for (op in operators) {
            val parts = splitByOperator(trimmed, op, limit = 2)
            if (parts.size == 2) {
                val left = parseValue(parts[0].trim())
                val right = parseValue(parts[1].trim())
                return compare(left, right, op)
            }
        }

        // No operator found - must be a boolean literal or parenthesized expression
        return parseValue(trimmed) as? Boolean
            ?: throw IllegalArgumentException("Invalid boolean expression: $trimmed")
    }

    /**
     * Split string by operator, respecting quotes.
     * Returns list with 1 element if operator not found.
     */
    private fun splitByOperator(expr: String, operator: String, limit: Int = 0): List<String> {
        val parts = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        var i = 0

        while (i < expr.length) {
            when {
                expr[i] == '"' && (i == 0 || expr[i-1] != '\\') -> {
                    inQuotes = !inQuotes
                    current.append(expr[i])
                    i++
                }
                !inQuotes && expr.substring(i).startsWith(operator) -> {
                    if (limit > 0 && parts.size >= limit - 1) {
                        // Reached limit, rest goes in last part
                        current.append(expr.substring(i))
                        break
                    }
                    parts.add(current.toString())
                    current = StringBuilder()
                    i += operator.length
                }
                else -> {
                    current.append(expr[i])
                    i++
                }
            }
        }

        parts.add(current.toString())
        return if (parts.size == 1 && parts[0] == expr) listOf(expr) else parts
    }

    /**
     * Parse a value from string representation.
     */
    private fun parseValue(str: String): Any? {
        val trimmed = str.trim()

        return when {
            trimmed == "null" -> null
            trimmed == "true" -> true
            trimmed == "false" -> false
            trimmed.startsWith("\"") && trimmed.endsWith("\"") -> {
                // String literal - unescape quotes
                trimmed.substring(1, trimmed.length - 1).replace("\\\"", "\"")
            }
            trimmed.toIntOrNull() != null -> trimmed.toInt()
            trimmed.toDoubleOrNull() != null -> trimmed.toDouble()
            else -> throw IllegalArgumentException("Cannot parse value: $trimmed")
        }
    }

    /**
     * Compare two values with given operator.
     */
    private fun compare(left: Any?, right: Any?, operator: String): Boolean {
        return when (operator) {
            "===" -> left === right || (left == null && right == null) || left == right
            "!==" -> !(left === right || (left == null && right == null) || left == right)
            "==" -> left == right
            "!=" -> left != right
            ">", "<", ">=", "<=" -> {
                // Numeric comparison
                val leftNum = (left as? Number)?.toDouble()
                    ?: throw IllegalArgumentException("Cannot compare non-numeric value: $left")
                val rightNum = (right as? Number)?.toDouble()
                    ?: throw IllegalArgumentException("Cannot compare non-numeric value: $right")

                when (operator) {
                    ">" -> leftNum > rightNum
                    "<" -> leftNum < rightNum
                    ">=" -> leftNum >= rightNum
                    "<=" -> leftNum <= rightNum
                    else -> false
                }
            }
            else -> throw IllegalArgumentException("Unknown operator: $operator")
        }
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