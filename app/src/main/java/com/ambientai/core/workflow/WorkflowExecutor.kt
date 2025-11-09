package com.ambientai.workflow

import android.content.Context
import com.ambientai.core.llm.GroqLlmService
import com.ambientai.core.log.LogManager
import com.ambientai.core.search.SearchService
import com.ambientai.core.task.TaskManager
import com.ambientai.core.tts.TextToSpeechService
import com.ambientai.data.entities.WorkflowExecution
import com.ambientai.data.entities.ActionExecution
import com.ambientai.data.repositories.WorkflowDefinitionRepository
import com.ambientai.data.repositories.WorkflowExecutionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray

class WorkflowExecutor(private val context: Context) {
    private val executionRepo = WorkflowExecutionRepository()
    private val workflowRepo = WorkflowDefinitionRepository()
    private var tts = TextToSpeechService(context)
    private val tasks = TaskManager()
    private val llm = GroqLlmService()
    private val search = SearchService()
    private val logs = LogManager()
    private var completionTriggers = mapOf<String, List<Long>>()

    fun loadCompletionTriggers() {
        completionTriggers = workflowRepo.getEnabled().flatMap { workflow ->
            try {
                JSONObject(workflow.definition).optJSONObject("triggers")?.optJSONArray("onWorkflowComplete")?.let { onComplete ->
                    (0 until onComplete.length()).map { onComplete.getString(it) to workflow.id }
                } ?: emptyList()
            } catch (e: Exception) { emptyList() }
        }.groupBy({ it.first }, { it.second })
    }
    private suspend fun triggerCompletionWorkflows(completedWorkflowName: String, originalContext: WorkflowExecutionContext) {
        completionTriggers[completedWorkflowName]?.forEach { workflowId ->
            try { executeById(workflowId, originalContext.variables) } catch (e: Exception) {}
        }
    }

    suspend fun executeById(workflowId: Long, contextOverride: Map<String, Any> = emptyMap()): WorkflowResult {
        val definition = workflowRepo.getById(workflowId) ?: return WorkflowResult.Failure("Workflow $workflowId not found")
        val context = WorkflowExecutionContext(workflowId = workflowId, workflowName = definition.name, transcript = "", matchedTrigger = "(programmatic)")
        context.variables.putAll(contextOverride)
        return execute(WorkflowMatch(definition, context))
    }
    suspend fun execute(match: WorkflowMatch): WorkflowResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val executionLog = WorkflowExecution(workflowId = match.definition.id, workflowName = match.definition.name, transcript = match.context.transcript, matchedTrigger = match.context.matchedTrigger, success = false, executionTimeMs = 0, timestamp = startTime)
        executionRepo.save(executionLog)
        try {
            val steps = JSONObject(match.definition.definition).getJSONArray("steps")
            (0 until steps.length()).forEach { i -> executeStep(steps.getJSONObject(i), match.context, executionLog.id, i, "$i") }
            executionLog.success = true
            executionLog.executionTimeMs = System.currentTimeMillis() - startTime
            executionRepo.save(executionLog)
            triggerCompletionWorkflows(match.definition.name, match.context)
            WorkflowResult.Success
        } catch (e: Exception) {
            executionLog.success = false
            executionLog.errorMessage = e.message
            executionLog.executionTimeMs = System.currentTimeMillis() - startTime
            executionRepo.save(executionLog)
            WorkflowResult.Failure(e.message ?: "Unknown error")
        }
    }

    private suspend fun executeStep(step: JSONObject, context: WorkflowExecutionContext, executionId: Long, stepIndex: Int, stepPath: String) {
        when (step.getString("action")) {
            "control.if" -> executeConditional(step, context, executionId, stepIndex, stepPath)
            else -> executeAction(step, context, executionId, stepIndex, stepPath)
        }
    }

    private suspend fun executeAction(step: JSONObject, context: WorkflowExecutionContext, executionId: Long, stepIndex: Int, stepPath: String) {
        val actionName = step.getString("action")
        val inputJson = step.getJSONObject("input")
        val outputVar = step.optString("output", null)
        val startTime = System.currentTimeMillis()
        try {
            val resolvedInput = resolveVariables(inputJson, context)
            val result = when (actionName.substringBefore(".")) {
                "task" -> tasks.execute(actionName, resolvedInput)
                "llm" -> llm.execute(actionName, resolvedInput)
                "tts" -> tts.execute(actionName, resolvedInput)
                "search" -> search.execute(actionName, resolvedInput)
                "log" -> logs.execute(actionName, resolvedInput)
                else -> throw UnknownActionException(actionName)
            }
            val latency = System.currentTimeMillis() - startTime
            if (outputVar != null && result != null) context.variables[outputVar] = result
            executionRepo.saveAction(ActionExecution(workflowExecutionId = executionId, stepIndex = stepIndex, stepPath = stepPath, actionName = actionName, inputJson = resolvedInput.toString(), outputJson = result?.toString() ?: "", success = true, latencyMs = latency, timestamp = System.currentTimeMillis()))
        } catch (e: Exception) {
            val latency = System.currentTimeMillis() - startTime
            executionRepo.saveAction(ActionExecution(workflowExecutionId = executionId, stepIndex = stepIndex, stepPath = stepPath, actionName = actionName, inputJson = inputJson.toString(), outputJson = "", success = false, errorMessage = e.message, latencyMs = latency, timestamp = System.currentTimeMillis()))
            throw e
        }
    }

    private suspend fun executeConditional(step: JSONObject, context: WorkflowExecutionContext, executionId: Long, stepIndex: Int, stepPath: String) {
        val conditionResult = evaluateCondition(step.getString("condition"), context)
        val branchSteps = if (conditionResult) step.getJSONArray("then") else step.optJSONArray("else")
        branchSteps?.let {
            val branchName = if (conditionResult) "then" else "else"
            (0 until it.length()).forEach { i -> executeStep(it.getJSONObject(i), context, executionId, stepIndex, "$stepPath.$branchName.$i") }
        }
    }
    private fun resolveVariables(input: JSONObject, context: WorkflowExecutionContext) = JSONObject().apply {
        input.keys().forEach { key -> put(key, resolveValue(input.get(key), context)) }
    }

    private fun resolveValue(value: Any, context: WorkflowExecutionContext): Any = when (value) {
        is String -> resolveString(value, context)
        is JSONObject -> resolveVariables(value, context)
        is JSONArray -> resolveArray(value, context)
        else -> value
    }
    private fun resolveArray(array: JSONArray, context: WorkflowExecutionContext) = JSONArray().apply {
        (0 until array.length()).forEach { put(resolveValue(array.get(it), context)) }
    }
    private fun resolveString(str: String, context: WorkflowExecutionContext): Any {
        val pattern = Regex("""\$([a-zA-Z_][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)*)""")
        val matches = pattern.findAll(str).toList()
        if (matches.isEmpty()) return str
        if (matches.size == 1 && matches[0].value == str) return resolveVariablePath(matches[0].groupValues[1], context)
        var result = str
        matches.forEach { match ->
            val value = resolveVariablePath(match.groupValues[1], context)
            result = result.replace(match.value, value.toString())
        }
        return result
    }

    private fun resolveVariablePath(path: String, context: WorkflowExecutionContext): Any {
        val parts = path.split(".")
        var current: Any = context.variables[parts[0]] ?: throw MissingVariableException(parts[0])
        for (i in 1 until parts.size) {
            val part = parts[i]
            current = when (current) {
                is JSONObject -> if (current.has(part)) current.get(part) else throw MissingVariableException(parts.subList(0, i+1).joinToString("."))
                is Map<*, *> -> current[part] ?: throw MissingVariableException(parts.subList(0, i+1).joinToString("."))
                is List<*> -> current.getOrNull(part.toIntOrNull() ?: throw IllegalArgumentException("Invalid list index: $part")) ?: throw MissingVariableException("${parts.subList(0, i+1).joinToString(".")} (index out of bounds)")
                else -> throw IllegalArgumentException("Cannot access property '$part' on ${current::class.simpleName}")
            }
        }
        return current
    }
    class MissingVariableException(varName: String) : Exception("Variable not found: \$$varName")
    private fun evaluateCondition(condition: String, context: WorkflowExecutionContext) = evaluateExpression(resolveVariablesInCondition(condition, context))
    private fun resolveVariablesInCondition(condition: String, context: WorkflowExecutionContext): String {
        val pattern = Regex("""\$([a-zA-Z_][a-zA-Z0-9_]*(?:\.[a-zA-Z0-9_]+)*)""")
        var result = condition
        pattern.findAll(condition).forEach { match ->
            val value = resolveVariablePath(match.groupValues[1], context)
            val replacement = when (value) {
                null -> "null"
                is String -> "\"${value.replace("\"", "\\\"")}\""
                is Boolean -> value.toString()
                is Number -> value.toString()
                else -> "\"$value\""
            }
            result = result.replace(match.value, replacement)
        }
        return result
    }
    private fun evaluateExpression(expr: String) = evaluateOr(expr.trim())
    private fun evaluateOr(expr: String): Boolean {
        val parts = splitByOperator(expr, "||")
        return if (parts.size == 1) evaluateAnd(parts[0]) else parts.any { evaluateAnd(it) }
    }
    private fun evaluateAnd(expr: String): Boolean {
        val parts = splitByOperator(expr, "&&")
        return if (parts.size == 1) evaluateComparison(parts[0]) else parts.all { evaluateComparison(it) }
    }

    private fun evaluateComparison(expr: String): Boolean {
        val trimmed = expr.trim()
        if (trimmed.startsWith("!")) return !evaluateComparison(trimmed.substring(1))
        listOf("===", "!==", "==", "!=", ">=", "<=", ">", "<").forEach { op ->
            val parts = splitByOperator(trimmed, op, limit = 2)
            if (parts.size == 2) return compare(parseValue(parts[0].trim()), parseValue(parts[1].trim()), op)
        }
        return parseValue(trimmed) as? Boolean ?: throw IllegalArgumentException("Invalid boolean expression: $trimmed")
    }
    private fun splitByOperator(expr: String, operator: String, limit: Int = 0): List<String> {
        val parts = mutableListOf<String>()
        var current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < expr.length) {
            when {
                expr[i] == '"' && (i == 0 || expr[i-1] != '\\') -> { inQuotes = !inQuotes; current.append(expr[i]); i++ }
                !inQuotes && expr.substring(i).startsWith(operator) -> {
                    if (limit > 0 && parts.size >= limit - 1) { current.append(expr.substring(i)); break }
                    parts.add(current.toString())
                    current = StringBuilder()
                    i += operator.length
                }
                else -> { current.append(expr[i]); i++ }
            }
        }
        parts.add(current.toString())
        return if (parts.size == 1 && parts[0] == expr) listOf(expr) else parts
    }
    private fun parseValue(str: String): Any? {
        val trimmed = str.trim()
        return when {
            trimmed == "null" -> null
            trimmed == "true" -> true
            trimmed == "false" -> false
            trimmed.startsWith("\"") && trimmed.endsWith("\"") -> trimmed.substring(1, trimmed.length - 1).replace("\\\"", "\"")
            trimmed.toIntOrNull() != null -> trimmed.toInt()
            trimmed.toDoubleOrNull() != null -> trimmed.toDouble()
            else -> throw IllegalArgumentException("Cannot parse value: $trimmed")
        }
    }
    private fun compare(left: Any?, right: Any?, operator: String): Boolean = when (operator) {
        "===" -> left === right || (left == null && right == null) || left == right
        "!==" -> !(left === right || (left == null && right == null) || left == right)
        "==" -> left == right
        "!=" -> left != right
        ">", "<", ">=", "<=" -> {
            val leftNum = (left as? Number)?.toDouble() ?: throw IllegalArgumentException("Cannot compare non-numeric value: $left")
            val rightNum = (right as? Number)?.toDouble() ?: throw IllegalArgumentException("Cannot compare non-numeric value: $right")
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
sealed class WorkflowResult {
    object Success : WorkflowResult()
    data class Failure(val error: String) : WorkflowResult()
}
class UnknownActionException(actionName: String) : Exception("Unknown action: $actionName")