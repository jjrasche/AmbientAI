package com.ambientai.workflow

import android.util.Log
import com.ambientai.core.browser.BrowserService
import com.ambientai.core.llm.GroqLlmService
import com.ambientai.core.log.LogManager
import com.ambientai.core.search.SearchService
import com.ambientai.core.task.TaskManager
import com.ambientai.core.time.TimeManager
import com.ambientai.core.tts.TextToSpeechService
import com.ambientai.core.ui.UiService
import com.ambientai.core.workflow.SlotFillingService
import com.ambientai.core.workflow.actions.WorkflowActionHandler
import com.ambientai.data.entities.WorkflowExecution
import com.ambientai.data.entities.ActionExecution
import com.ambientai.data.repositories.IWorkflowDefinitionRepository
import com.ambientai.data.repositories.IWorkflowExecutionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WorkflowExecutor @Inject constructor(
    private val executionRepo: IWorkflowExecutionRepository,
    private val workflowRepo: IWorkflowDefinitionRepository,
    private val tts: TextToSpeechService,
    private val tasks: TaskManager,
    private val llm: GroqLlmService,
    private val search: SearchService,
    private val logs: LogManager,
    private val time: TimeManager,
    private val workflowActions: WorkflowActionHandler,
    private val ui: UiService,
    private val mediaHandler: com.ambientai.core.media.MediaHandler,
    private val browser: BrowserService,
    private val subscriptionHandler: com.ambientai.core.media.SubscriptionWorkflowHandler,
    private val slotFilling: SlotFillingService
) {
    companion object { private const val TAG = "WorkflowExecutor" }
    private var completionTriggers = mapOf<String, List<Long>>()

    fun loadCompletionTriggers() { completionTriggers = workflowRepo.getEnabled().flatMap { workflow -> runCatching { JSONObject(workflow.definition).optJSONObject("triggers")?.optJSONArray("onWorkflowComplete")?.let { onComplete -> (0 until onComplete.length()).map { onComplete.getString(it) to workflow.id } } ?: emptyList() }.getOrElse { emptyList() } }.groupBy({ it.first }, { it.second }) }
    private suspend fun triggerCompletionWorkflows(completedWorkflowName: String, originalContext: WorkflowExecutionContext) = completionTriggers[completedWorkflowName]?.forEach { workflowId -> runCatching { executeById(workflowId, originalContext.variables) } }
    suspend fun executeById(workflowId: Long, contextOverride: Map<String, Any> = emptyMap()): WorkflowResult = workflowRepo.getById(workflowId)?.let { definition -> WorkflowExecutionContext(workflowId = workflowId, workflowName = definition.name, transcript = "", matchedTrigger = "(programmatic)").also { it.variables.putAll(contextOverride) }.let { execute(WorkflowMatch(definition, it)) } } ?: WorkflowResult.Failure("Workflow $workflowId not found")

    /**
     * Execute multiple workflows in sequence (for compound commands like
     * "play music stop that start a task").
     */
    suspend fun executeMultiple(matches: List<WorkflowMatch>): List<WorkflowResult> {
        Log.d(TAG, "⚙ EXECUTING ${matches.size} SEQUENTIAL WORKFLOWS")
        return matches.mapIndexed { index, match ->
            Log.d(TAG, "   ↳ Workflow ${index + 1}/${matches.size}: ${match.definition.name}")
            execute(match)
        }
    }
    suspend fun execute(match: WorkflowMatch): WorkflowResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "⚙ EXECUTING WORKFLOW: ${match.definition.name}")
        val startTime = System.currentTimeMillis()
        val executionLog = executionRepo.save(WorkflowExecution(workflowId = match.definition.id, workflowName = match.definition.name, transcript = match.context.transcript, matchedTrigger = match.context.matchedTrigger, success = false, executionTimeMs = 0, timestamp = startTime))
        runCatching {
            val workflowDef = JSONObject(match.definition.definition)
            val filledSlots = slotFilling.fillSlots(workflowDef, match.context.transcript)
            filledSlots.forEach { (key, value) -> match.context.variables[key] = value }
            workflowDef.getJSONArray("steps").let { steps -> (0 until steps.length()).forEach { i -> executeStep(steps.getJSONObject(i), match.context, executionLog.id, i, "$i") } }
            executionLog.apply { success = true; executionTimeMs = System.currentTimeMillis() - startTime }.also { executionRepo.save(it) }
            Log.d(TAG, "✓ WORKFLOW SUCCESS: ${match.definition.name} (${System.currentTimeMillis() - startTime}ms)")
            triggerCompletionWorkflows(match.definition.name, match.context)
            WorkflowResult.Success(match.context.variables)
        }.getOrElse { e ->
            executionLog.apply { success = false; errorMessage = e.message; executionTimeMs = System.currentTimeMillis() - startTime }.also { executionRepo.save(it) }
            Log.e(TAG, "✖ WORKFLOW FAILED: ${match.definition.name} - ${e.message}", e)
            WorkflowResult.Failure(e.message ?: "Unknown error")
        }
    }
    private suspend fun executeStep(step: JSONObject, context: WorkflowExecutionContext, executionId: Long, stepIndex: Int, stepPath: String) = when (step.getString("action")) { "control.if" -> executeConditional(step, context, executionId, stepIndex, stepPath); else -> executeAction(step, context, executionId, stepIndex, stepPath) }
    private suspend fun executeAction(step: JSONObject, context: WorkflowExecutionContext, executionId: Long, stepIndex: Int, stepPath: String) {
        val actionName = step.getString("action")
        val inputJson = step.optJSONObject("input") ?: JSONObject()
        val outputVar = step.optString("output", null)
        val startTime = System.currentTimeMillis()
        Log.d(TAG, "  ↳ ACTION: $actionName")
        runCatching {
            val resolvedInput = resolveVariables(inputJson, context)
            Log.d(TAG, "    → Input: $resolvedInput")
            val result = when (actionName.substringBefore(".")) { "task" -> tasks.execute(actionName, resolvedInput); "llm" -> llm.execute(actionName, resolvedInput); "tts" -> tts.execute(actionName, resolvedInput); "search" -> search.execute(actionName, resolvedInput); "log" -> logs.execute(actionName, resolvedInput); "time" -> time.execute(actionName, resolvedInput); "timer" -> time.execute(actionName, resolvedInput); "workflow" -> workflowActions.execute(actionName, resolvedInput); "ui" -> if (actionName == "ui.awaitChoice") ui.executeAsync(actionName, resolvedInput) else ui.execute(actionName, resolvedInput); "media" -> mediaHandler.execute(actionName, resolvedInput); "browser" -> browser.execute(actionName, resolvedInput); "subscription" -> subscriptionHandler.execute(actionName, resolvedInput); else -> throw UnknownActionException(actionName) }
            Log.d(TAG, "    → Output: $result")
            outputVar?.takeIf { result != null }?.let { context.variables[it] = result!! }
            executionRepo.saveAction(ActionExecution(workflowExecutionId = executionId, stepIndex = stepIndex, stepPath = stepPath, actionName = actionName, inputJson = resolvedInput.toString(), outputJson = result?.toString() ?: "", success = true, latencyMs = System.currentTimeMillis() - startTime, timestamp = startTime))
            Log.d(TAG, "    ✓ ACTION SUCCESS: $actionName (${System.currentTimeMillis() - startTime}ms)")
        }.onFailure { e ->
            executionRepo.saveAction(ActionExecution(workflowExecutionId = executionId, stepIndex = stepIndex, stepPath = stepPath, actionName = actionName, inputJson = inputJson.toString(), outputJson = "", success = false, errorMessage = e.message, latencyMs = System.currentTimeMillis() - startTime, timestamp = startTime))
            Log.e(TAG, "    ✖ ACTION FAILED: $actionName - ${e.message}", e)
            throw e
        }
    }
    private suspend fun executeConditional(step: JSONObject, context: WorkflowExecutionContext, executionId: Long, stepIndex: Int, stepPath: String): Unit = evaluateCondition(step.getString("condition"), context).let { conditionResult -> (if (conditionResult) step.getJSONArray("then") else step.optJSONArray("else"))?.let { branchSteps -> val branchName = if (conditionResult) "then" else "else"; (0 until branchSteps.length()).forEach { i -> executeStep(branchSteps.getJSONObject(i), context, executionId, stepIndex, "$stepPath.$branchName.$i") } }; Unit }
    private fun resolveVariables(input: JSONObject, context: WorkflowExecutionContext) = JSONObject().apply { input.keys().forEach { key -> put(key, resolveValue(input.get(key), context)) } }
    private fun resolveValue(value: Any, context: WorkflowExecutionContext): Any = when (value) { is String -> resolveString(value, context); is JSONObject -> resolveVariables(value, context); is JSONArray -> resolveArray(value, context); else -> value }
    private fun resolveArray(array: JSONArray, context: WorkflowExecutionContext) = JSONArray().apply { (0 until array.length()).forEach { put(resolveValue(array.get(it), context)) } }
    private fun resolveString(str: String, context: WorkflowExecutionContext): Any = Regex("""\$([a-zA-Z_][a-zA-Z0-9_]*(?:(?:\.[a-zA-Z0-9_]+)|(?:\[\d+\]))*)""").findAll(str).toList().let { matches -> when { matches.isEmpty() -> str; matches.size == 1 && matches[0].value == str -> resolveVariablePath(matches[0].groupValues[1], context); else -> matches.fold(str) { result, match -> result.replace(match.value, resolveVariablePath(match.groupValues[1], context).toString()) } } }
    private fun resolveVariablePath(path: String, context: WorkflowExecutionContext): Any {
        val segments = parsePathSegments(path)
        val varName = segments.first()
        return segments.drop(1).fold(context.variables[varName] as Any? ?: throw MissingVariableException(varName)) { current, segment ->
            when {
                segment.startsWith("[") -> {
                    val index = segment.removeSurrounding("[", "]").toInt()
                    when (current) {
                        is JSONArray -> if (index >= 0 && index < current.length()) current.get(index) else throw MissingVariableException("$path (index out of bounds)")
                        is List<*> -> current.getOrNull(index) ?: throw MissingVariableException("$path (index out of bounds)")
                        else -> throw IllegalArgumentException("Cannot index into ${current::class.simpleName}")
                    }
                }
                else -> when (current) {
                    is JSONObject -> if (current.has(segment)) current.get(segment) else throw MissingVariableException(path)
                    is JSONArray -> if (segment == "length") current.length() else segment.toIntOrNull()?.let { i -> if (i >= 0 && i < current.length()) current.get(i) else throw MissingVariableException("$path (index out of bounds)") } ?: throw IllegalArgumentException("Invalid array index: $segment")
                    is Map<*, *> -> current[segment] ?: throw MissingVariableException(path)
                    is List<*> -> if (segment == "length") current.size else current.getOrNull(segment.toIntOrNull() ?: throw IllegalArgumentException("Invalid list index: $segment")) ?: throw MissingVariableException("$path (index out of bounds)")
                    is String -> if (segment == "length") current.length else tryParseJsonAndAccess(current, segment, path)
                    else -> throw IllegalArgumentException("Cannot access property '$segment' on ${current::class.simpleName}")
                }
            }
        }
    }
    private fun parsePathSegments(path: String): List<String> = mutableListOf<String>().also { segments -> val current = StringBuilder(); var i = 0; while (i < path.length) { when { path[i] == '.' -> { if (current.isNotEmpty()) segments.add(current.toString()); current.clear(); i++ }; path[i] == '[' -> { if (current.isNotEmpty()) segments.add(current.toString()); current.clear(); val end = path.indexOf(']', i); segments.add(path.substring(i, end + 1)); i = end + 1 }; else -> { current.append(path[i]); i++ } } }; if (current.isNotEmpty()) segments.add(current.toString()) }
    private fun tryParseJsonAndAccess(jsonString: String, property: String, fullPath: String): Any = runCatching { jsonString.trim().let { s -> if (s.startsWith("```json")) s.substringAfter("```json").substringBefore("```").trim() else if (s.startsWith("```")) s.substringAfter("```").substringBefore("```").trim() else s }.let { JSONObject(it).let { json -> if (json.has(property)) json.get(property) else throw MissingVariableException(fullPath) } } }.getOrElse { Log.e(TAG, "Failed to parse JSON string for path $fullPath. String value: \"$jsonString\"", it); throw IllegalArgumentException("Cannot access property '$property' on String (not valid JSON). Value: \"${jsonString.take(100)}${if (jsonString.length > 100) "..." else ""}\"") }
    class MissingVariableException(varName: String) : Exception("Variable not found: \$$varName")
    private fun evaluateCondition(condition: String, context: WorkflowExecutionContext) = evaluateExpression(resolveVariablesInCondition(condition, context))
    private fun resolveVariablesInCondition(condition: String, context: WorkflowExecutionContext): String = Regex("""\$([a-zA-Z_][a-zA-Z0-9_]*(?:(?:\.[a-zA-Z0-9_]+)|(?:\[\d+\]))*)""").findAll(condition).fold(condition) { result, match -> result.replace(match.value, resolveVariablePath(match.groupValues[1], context).let { value -> when (value) { null -> "null"; is String -> "\"${value.replace("\"", "\\\"")}\""; is Boolean -> value.toString(); is Number -> value.toString(); else -> "\"$value\"" } }) }
    private fun evaluateExpression(expr: String) = evaluateOr(expr.trim())
    private fun evaluateOr(expr: String) = splitByOperator(expr, "||").let { parts -> if (parts.size == 1) evaluateAnd(parts[0]) else parts.any { evaluateAnd(it) } }
    private fun evaluateAnd(expr: String) = splitByOperator(expr, "&&").let { parts -> if (parts.size == 1) evaluateComparison(parts[0]) else parts.all { evaluateComparison(it) } }
    private fun evaluateComparison(expr: String): Boolean = expr.trim().let { trimmed -> when { trimmed.startsWith("!") -> !evaluateComparison(trimmed.substring(1)); else -> listOf("===", "!==", "==", "!=", ">=", "<=", ">", "<").firstNotNullOfOrNull { op -> splitByOperator(trimmed, op, limit = 2).takeIf { it.size == 2 }?.let { parts -> compare(parseValue(parts[0].trim()), parseValue(parts[1].trim()), op) } } ?: (parseValue(trimmed) as? Boolean ?: throw IllegalArgumentException("Invalid boolean expression: $trimmed")) } }
    private fun splitByOperator(expr: String, operator: String, limit: Int = 0): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < expr.length) {
            val char = expr[i]
            when {
                char == '"' && (i == 0 || expr[i-1] != '\\') -> { current.append(char); inQuotes = !inQuotes; i++ }
                !inQuotes && expr.substring(i).startsWith(operator) -> {
                    if (limit > 0 && parts.size >= limit - 1) { current.append(expr.substring(i)); break }
                    else { parts.add(current.toString()); current.clear(); i += operator.length }
                }
                else -> { current.append(char); i++ }
            }
        }
        parts.add(current.toString())
        return parts.takeIf { it.size > 1 } ?: listOf(expr)
    }
    private fun parseValue(str: String) = str.trim().let { trimmed -> when { trimmed == "null" -> null; trimmed == "true" -> true; trimmed == "false" -> false; trimmed.startsWith("\"") && trimmed.endsWith("\"") -> trimmed.substring(1, trimmed.length - 1).replace("\\\"", "\""); trimmed.toIntOrNull() != null -> trimmed.toInt(); trimmed.toDoubleOrNull() != null -> trimmed.toDouble(); else -> throw IllegalArgumentException("Cannot parse value: $trimmed") } }
    private fun compare(left: Any?, right: Any?, operator: String): Boolean = when (operator) { "===" -> left === right || (left == null && right == null) || left == right; "!==" -> !(left === right || (left == null && right == null) || left == right); "==" -> left == right; "!=" -> left != right; ">", "<", ">=", "<=" -> { val leftNum = (left as? Number)?.toDouble() ?: throw IllegalArgumentException("Cannot compare non-numeric value: $left"); val rightNum = (right as? Number)?.toDouble() ?: throw IllegalArgumentException("Cannot compare non-numeric value: $right"); when (operator) { ">" -> leftNum > rightNum; "<" -> leftNum < rightNum; ">=" -> leftNum >= rightNum; "<=" -> leftNum <= rightNum; else -> false } }; else -> throw IllegalArgumentException("Unknown operator: $operator") }
}
sealed class WorkflowResult {
    data class Success(val variables: Map<String, Any>) : WorkflowResult()
    data class Failure(val error: String) : WorkflowResult()
}
class UnknownActionException(actionName: String) : Exception("Unknown action: $actionName")
