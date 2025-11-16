package com.ambientai.testing

import android.util.Log
import com.ambientai.data.repositories.*
import com.ambientai.debug.SttSimulator
import com.ambientai.workflow.WorkflowRouter
import com.ambientai.workflow.WorkflowExecutor
import kotlinx.coroutines.delay
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RegressionTestExecutor @Inject constructor(
    private val sttSimulator: SttSimulator,
    private val workflowRouter: WorkflowRouter,
    private val workflowExecutor: WorkflowExecutor,
    private val workflowExecRepo: IWorkflowExecutionRepository,
    private val actionExecRepo: IActionExecutionRepository,
    private val taskRepo: ITaskRepository,
    private val transcriptRepo: ITranscriptRepository,
    private val mediaHistoryRepo: IMediaHistoryRepository
) {
    companion object { private const val TAG = "RegressionTest" }

    suspend fun runTest(scenario: RegressionTestScenario): TestResult {
        Log.d(TAG, "🧪 RUNNING TEST: ${scenario.testId}")
        val startTime = System.currentTimeMillis()
        val failures = mutableListOf<String>()

        try {
            // 1. Capture initial state
            val initialState = captureState()

            // 2. Apply preconditions if any
            if (scenario.input.preconditions.isNotEmpty()) {
                applyPreconditions(scenario.input.preconditions)
            }

            // 3. Save a transcript first (workflows expect transcriptId)
            val transcript = com.ambientai.data.entities.Transcript(
                text = scenario.input.utterance,
                audioFilePath = "",  // No audio for regression tests
                timestamp = System.currentTimeMillis(),
                excludeFromContext = true  // Don't pollute context with test transcripts
            )
            val savedTranscript = transcriptRepo.save(transcript)

            // 4. Execute the test - route and execute workflow
            val match = workflowRouter.route(scenario.input.utterance, savedTranscript.id, isPartial = false)
            if (match != null) {
                Log.d(TAG, "  → Matched workflow: ${match.definition.name}")
                Log.d(TAG, "  → Context variables: ${match.context.variables.keys}")
                workflowExecutor.execute(match)
            } else {
                Log.w(TAG, "  → No workflow matched for: ${scenario.input.utterance}")
            }

            // 5. Wait for async processing
            delay(2000)

            // 6. Capture final state
            val finalState = captureState()

            // 7. Assert expectations
            assertExpectations(scenario.expected, initialState, finalState, failures)

            val durationMs = System.currentTimeMillis() - startTime
            val passed = failures.isEmpty()

            Log.d(TAG, if (passed) "✅ PASSED: ${scenario.testId} (${durationMs}ms)"
                       else "❌ FAILED: ${scenario.testId} - ${failures.joinToString(", ")}")

            return TestResult(
                testId = scenario.testId,
                passed = passed,
                durationMs = durationMs,
                failures = failures,
                details = mapOf(
                    "initial_state" to initialState.toMap(),
                    "final_state" to finalState.toMap()
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "💥 TEST ERROR: ${scenario.testId}", e)
            return TestResult(
                testId = scenario.testId,
                passed = false,
                durationMs = System.currentTimeMillis() - startTime,
                failures = listOf("Exception: ${e.message}"),
                details = mapOf("error" to (e.message ?: "Unknown error"))
            )
        }
    }

    private fun captureState() = SystemState(
        workflowExecutionCount = workflowExecRepo.count(),
        actionExecutionCount = actionExecRepo.count(),
        taskCount = taskRepo.count(),
        transcriptCount = transcriptRepo.count(),
        mediaHistoryCount = mediaHistoryRepo.count(),
        recentWorkflows = workflowExecRepo.getRecent(5),
        recentActions = actionExecRepo.getRecent(10),
        activeTasks = taskRepo.getByStatus(com.ambientai.data.entities.TaskStatus.ACTIVE),
        recentMediaHistory = mediaHistoryRepo.getRecent(5)
    )

    private fun applyPreconditions(preconditions: Map<String, Any>) = Unit.also {
        // Handle preconditions like setting up music player state, creating test tasks, etc.
        Log.d(TAG, "📋 Applying preconditions: $preconditions")
        // TODO: Implement based on actual precondition needs
    }

    private fun assertExpectations(
        expected: TestExpectations,
        initialState: SystemState,
        finalState: SystemState,
        failures: MutableList<String>
    ) {
        // Workflow execution checks
        expected.workflowMatched?.let { expectedWorkflow ->
            val recentWorkflow = finalState.recentWorkflows.firstOrNull()
            if (recentWorkflow?.workflowName != expectedWorkflow) {
                failures.add("Expected workflow '$expectedWorkflow', got '${recentWorkflow?.workflowName}'")
            }
            Unit
        }

        expected.workflowExecuted?.let { shouldExecute ->
            val newExecutions = finalState.workflowExecutionCount - initialState.workflowExecutionCount
            if (shouldExecute && newExecutions == 0L) {
                failures.add("Expected workflow to execute, but no new executions found")
            } else if (!shouldExecute && newExecutions > 0) {
                failures.add("Expected no workflow execution, but $newExecutions new executions found")
            }
            Unit
        }

        expected.workflowSuccess?.let { shouldSucceed ->
            val recentWorkflow = finalState.recentWorkflows.firstOrNull()
            if (recentWorkflow != null && recentWorkflow.success != shouldSucceed) {
                failures.add("Expected workflow success=$shouldSucceed, got ${recentWorkflow.success}")
            }
        }

        // Action execution checks
        expected.actionsExecuted?.let { expectedActions ->
            val newActions = finalState.recentActions.filter { action ->
                !initialState.recentActions.any { it.id == action.id }
            }
            val executedActionNames = newActions.map { it.actionName }
            expectedActions.forEach { expectedAction ->
                if (!executedActionNames.contains(expectedAction)) {
                    failures.add("Expected action '$expectedAction' not executed. Got: ${executedActionNames.joinToString(", ")}")
                }
            }
        }

        // Database change checks
        expected.databaseChanges?.forEach { (entityType, assertion) ->
            when (entityType) {
                "WorkflowExecution" -> {
                    val count = (finalState.workflowExecutionCount - initialState.workflowExecutionCount).toInt()
                    assertion.count?.let { if (count != it) failures.add("Expected $it WorkflowExecution, got $count") }
                    assertion.minCount?.let { if (count < it) failures.add("Expected at least $it WorkflowExecution, got $count") }
                }
                "ActionExecution" -> {
                    val count = (finalState.actionExecutionCount - initialState.actionExecutionCount).toInt()
                    assertion.count?.let { if (count != it) failures.add("Expected $it ActionExecution, got $count") }
                    assertion.minCount?.let { if (count < it) failures.add("Expected at least $it ActionExecution, got $count") }
                }
                "Task" -> {
                    val count = (finalState.taskCount - initialState.taskCount).toInt()
                    assertion.count?.let { if (count != it) failures.add("Expected $it Task created, got $count") }
                    assertion.nameContains?.let { nameFragment ->
                        val hasMatchingTask = finalState.activeTasks.any { it.name.contains(nameFragment, ignoreCase = true) }
                        if (!hasMatchingTask) failures.add("Expected task with name containing '$nameFragment'")
                    }
                }
                "MediaHistory" -> {
                    val count = (finalState.mediaHistoryCount - initialState.mediaHistoryCount).toInt()
                    assertion.count?.let { expectedCount -> if (count != expectedCount) failures.add("Expected $expectedCount MediaHistory created, got $count") }
                    assertion.minCount?.let { minCount -> if (count < minCount) failures.add("Expected at least $minCount MediaHistory, got $count") }
                }
            }
        }
    }
}

data class SystemState(
    val workflowExecutionCount: Long,
    val actionExecutionCount: Long,
    val taskCount: Long,
    val transcriptCount: Long,
    val mediaHistoryCount: Long,
    val recentWorkflows: List<com.ambientai.data.entities.WorkflowExecution>,
    val recentActions: List<com.ambientai.data.entities.ActionExecution>,
    val activeTasks: List<com.ambientai.data.entities.Task>,
    val recentMediaHistory: List<com.ambientai.data.entities.MediaHistory>
) {
    fun toMap() = mapOf(
        "workflow_executions" to workflowExecutionCount,
        "action_executions" to actionExecutionCount,
        "tasks" to taskCount,
        "transcripts" to transcriptCount,
        "media_history" to mediaHistoryCount
    )
}
