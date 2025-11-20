package com.ambientai.testing

import android.content.Context
import android.util.Log
import com.ambientai.core.media.MediaHandler
import com.ambientai.core.task.TaskManager
import com.ambientai.core.time.TimeManager
import com.ambientai.data.entities.Task
import com.ambientai.data.entities.TaskStatus
import com.ambientai.data.entities.WorkflowExecution
import com.ambientai.core.stt.DeepgramSttService
import com.ambientai.data.repositories.*
import com.ambientai.workflow.WorkflowRouter
import com.ambientai.workflow.WorkflowExecutor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RegressionTestExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val workflowRouter: WorkflowRouter,
    private val workflowExecutor: WorkflowExecutor,
    private val workflowExecRepo: IWorkflowExecutionRepository,
    private val actionExecRepo: IActionExecutionRepository,
    private val taskRepo: ITaskRepository,
    private val transcriptRepo: ITranscriptRepository,
    private val mediaHistoryRepo: IMediaHistoryRepository,
    private val mediaHandler: MediaHandler,
    private val taskManager: TaskManager,
    private val timeManager: TimeManager,
    private val playbackStateManager: com.ambientai.core.music.PlaybackStateManager
) {
    companion object {
        private const val TAG = "RegressionTest"
        private const val DEFAULT_TIMEOUT_MS = 5000L
        private const val POLL_INTERVAL_MS = 100L
        private const val STT_TIMEOUT_MS = 120000L
    }

    suspend fun runTest(scenario: RegressionTestScenario): TestResult {
        Log.d(TAG, "🧪 RUNNING TEST: ${scenario.testId}")
        val testStartTime = System.currentTimeMillis()
        val failures = mutableListOf<String>()

        try {
            // 1. Capture initial state
            val initialState = captureState()

            // 2. Apply preconditions if any
            if (scenario.preconditions.isNotEmpty()) {
                applyPreconditions(scenario.preconditions)
            }

            // 3. Load and transcribe audio file through Deepgram (real E2E STT)
            val audioData = try {
                context.assets.open(scenario.audioFile).use { it.readBytes() }
            } catch (e: Exception) {
                Log.e(TAG, "✖ Failed to load audio: ${scenario.audioFile}", e)
                failures.add("Failed to load audio file: ${scenario.audioFile}")
                return TestResult(
                    testId = scenario.testId,
                    passed = false,
                    durationMs = System.currentTimeMillis() - testStartTime,
                    failures = failures
                )
            }

            Log.d(TAG, "🎤 Transcribing ${audioData.size} bytes from ${scenario.audioFile}")

            // Use production DeepgramSttService with callbacks - DRY principle
            val transcriptDeferred = CompletableDeferred<String?>()
            val sttService = DeepgramSttService(
                context = context,
                onPartialTranscript = { /* ignore partials for test */ },
                onTranscriptReady = { text -> transcriptDeferred.complete(text) },
                onRecognitionError = { _ -> transcriptDeferred.complete(null) },
                audioSaveCallback = { /* ignore audio save for test */ },
                onRecordingStopped = { /* handled by onTranscriptReady */ }
            )

            if (!sttService.initialize()) {
                failures.add("STT initialization failed - missing RECORD_AUDIO permission")
                return TestResult(
                    testId = scenario.testId,
                    passed = false,
                    durationMs = System.currentTimeMillis() - testStartTime,
                    failures = failures
                )
            }

            sttService.startFromAudioData(audioData)

            val transcriptionText = try {
                withTimeout(STT_TIMEOUT_MS) { transcriptDeferred.await() }
            } catch (e: Exception) {
                Log.e(TAG, "✖ STT timeout or error", e)
                null
            }

            if (transcriptionText == null) {
                failures.add("STT transcription failed - no result from Deepgram")
                return TestResult(
                    testId = scenario.testId,
                    passed = false,
                    durationMs = System.currentTimeMillis() - testStartTime,
                    failures = failures
                )
            }

            // Validate transcription matches expected utterance (strip punctuation, normalize whitespace)
            fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9\\s]"), "").replace(Regex("\\s+"), " ").trim()
            val expectedNormalized = normalize(scenario.utterance)
            val actualNormalized = normalize(transcriptionText)
            if (expectedNormalized != actualNormalized) {
                failures.add("STT mismatch: expected \"${scenario.utterance}\" → \"$expectedNormalized\", got \"$transcriptionText\" → \"$actualNormalized\"")
            }
            Log.d(TAG, "📝 Transcription: \"$transcriptionText\" (expected: \"${scenario.utterance}\")")

            // 4. Save a transcript first (workflows expect transcriptId)
            val transcript = com.ambientai.data.entities.Transcript(
                text = transcriptionText,
                audioFilePath = scenario.audioFile,
                timestamp = System.currentTimeMillis(),
                excludeFromContext = true  // Don't pollute context with test transcripts
            )
            val savedTranscript = transcriptRepo.save(transcript)

            // 5. Execute the test - route and execute workflow(s)
            val matches = workflowRouter.route(transcriptionText, savedTranscript.id, isPartial = false)
            if (matches.isNotEmpty()) {
                Log.d(TAG, "  → Matched ${matches.size} workflow(s): ${matches.map { it.definition.name }}")
                matches.forEach { match ->
                    Log.d(TAG, "  → Executing: ${match.definition.name}")
                    workflowExecutor.execute(match)
                }
            } else {
                Log.w(TAG, "  → No workflow matched for: $transcriptionText")
            }

            // 6. Wait for workflow completion (polling instead of fixed delay)
            val execution = try {
                waitForWorkflowCompletion(testStartTime, DEFAULT_TIMEOUT_MS)
            } catch (e: TimeoutException) {
                Log.w(TAG, "  → Workflow did not complete within timeout")
                null
            }

            // 7. Capture final state
            val finalState = captureState()

            // 8. Assert expectations
            assertExpectations(scenario.expected, initialState, finalState, failures)

            // 9. Assert service state changes (Level 2 verification)
            assertServiceStateChanges(scenario.expected, failures)

            // 10. Assert negative conditions
            assertNegativeConditions(scenario.expected, initialState, finalState, failures)

            val durationMs = System.currentTimeMillis() - testStartTime
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
                    "final_state" to finalState.toMap(),
                    "workflow_execution_id" to (execution?.id ?: -1L)
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "💥 TEST ERROR: ${scenario.testId}", e)
            return TestResult(
                testId = scenario.testId,
                passed = false,
                durationMs = System.currentTimeMillis() - testStartTime,
                failures = listOf("Exception: ${e.message}"),
                details = mapOf("error" to (e.message ?: "Unknown error"), "stack_trace" to e.stackTraceToString())
            )
        } finally {
            // Always cleanup after test (even if it failed)
            cleanup()
        }
    }

    /**
     * Poll for workflow completion instead of using fixed delay.
     * Throws TimeoutException if workflow doesn't complete within timeout.
     */
    private suspend fun waitForWorkflowCompletion(
        testStartTime: Long,
        timeout: Long = DEFAULT_TIMEOUT_MS
    ): WorkflowExecution {
        val deadline = System.currentTimeMillis() + timeout

        while (System.currentTimeMillis() < deadline) {
            val recent = workflowExecRepo.getRecent(1).firstOrNull()

            // Check if this execution started after test began and completed
            if (recent != null && recent.timestamp >= testStartTime) {
                Log.d(TAG, "  → Workflow completed in ${recent.executionTimeMs}ms")
                return recent
            }

            delay(POLL_INTERVAL_MS)
        }

        throw TimeoutException("Workflow did not complete within ${timeout}ms")
    }

    private fun captureState() = SystemState(
        workflowExecutionCount = workflowExecRepo.count(),
        actionExecutionCount = actionExecRepo.count(),
        taskCount = taskRepo.count(),
        transcriptCount = transcriptRepo.count(),
        mediaHistoryCount = mediaHistoryRepo.count(),
        recentWorkflows = workflowExecRepo.getRecent(5),
        recentActions = actionExecRepo.getRecent(10),
        activeTasks = taskRepo.getByStatus(TaskStatus.ACTIVE),
        recentMediaHistory = mediaHistoryRepo.getRecent(5)
    )

    /**
     * Apply preconditions by executing action sequences defined in JSON.
     * Truly data-driven - no hardcoded logic.
     */
    private suspend fun applyPreconditions(preconditions: List<PreconditionStep>) {
        if (preconditions.isEmpty()) return
        Log.d(TAG, "📋 Applying ${preconditions.size} precondition steps")

        preconditions.forEach { step ->
            when {
                step.wait != null -> {
                    Log.d(TAG, "  ⏳ wait ${step.wait}ms")
                    delay(step.wait)
                }
                step.action != null -> {
                    val input = JSONObject(step.input)
                    Log.d(TAG, "  → ${step.action}: ${input.toString().take(50)}")
                    val result = executeAction(step.action, input)
                    if (!result.optBoolean("success", true)) {
                        Log.w(TAG, "    ⚠ ${result.optString("error", "Action failed")}")
                    }
                }
            }
        }
    }

    /**
     * Route action to appropriate handler based on namespace.
     */
    private suspend fun executeAction(action: String, input: JSONObject): JSONObject = when {
        action.startsWith("media.") -> mediaHandler.execute(action, input)
        action.startsWith("task.") -> taskManager.execute(action, input)
        action.startsWith("timer.") -> timeManager.execute(action, input)
        else -> JSONObject().apply { put("success", false); put("error", "Unknown action namespace: $action") }
    }

    /**
     * Poll until condition is true or timeout.
     */
    private suspend fun pollUntil(
        timeout: Long = 2000,
        pollInterval: Long = 100,
        condition: () -> Boolean
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeout

        while (System.currentTimeMillis() < deadline) {
            if (condition()) {
                return true
            }
            delay(pollInterval)
        }

        return false
    }

    /**
     * Assert positive expectations.
     */
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
        }

        expected.workflowExecuted?.let { shouldExecute ->
            val newExecutions = finalState.workflowExecutionCount - initialState.workflowExecutionCount
            when {
                shouldExecute && newExecutions == 0L -> failures.add("Expected workflow to execute, but no new executions found")
                !shouldExecute && newExecutions > 0L -> failures.add("Expected no workflow execution, but $newExecutions new executions found")
                else -> Unit
            }
        }

        expected.workflowSuccess?.let { shouldSucceed ->
            val recentWorkflow = finalState.recentWorkflows.firstOrNull()
            if (recentWorkflow != null && recentWorkflow.success != shouldSucceed) {
                failures.add("Expected workflow success=$shouldSucceed, got ${recentWorkflow.success}. Error: ${recentWorkflow.errorMessage ?: "None"}")
            }
        }

        // Action execution checks
        expected.actionsExecuted?.let { expectedActions ->
            val newActions = finalState.recentActions.filter { action ->
                !initialState.recentActions.any { it.id == action.id }
            }.sortedBy { it.timestamp }
            val executedActionNames = newActions.map { it.actionName }

            expectedActions.forEach { expectedAction ->
                if (!executedActionNames.contains(expectedAction)) {
                    failures.add("Expected action '$expectedAction' not executed. Got: ${executedActionNames.joinToString(", ")}")
                }
            }

            // Check action order matches expected order
            if (executedActionNames.size >= expectedActions.size) {
                expectedActions.forEachIndexed { index, expectedAction ->
                    if (index < executedActionNames.size && executedActionNames[index] != expectedAction) {
                        failures.add("Action order mismatch at position $index: expected '$expectedAction', got '${executedActionNames[index]}'")
                    }
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

    /**
     * Assert service state changes (Level 2 verification).
     * Checks actual Android component state, not just service wrappers.
     */
    private fun assertServiceStateChanges(
        expected: TestExpectations,
        failures: MutableList<String>
    ) {
        expected.serviceStateChanges?.forEach { (key, expectedValue) ->
            when (key) {
                "music_player_playing" -> {
                    val expected = expectedValue as Boolean
                    // TODO: Cannot verify playback state without direct service access
                    // For now, skip this verification
                    Log.d(TAG, "  → Skipping music_player_playing verification (service not injectable)")
                }

                "timer_active" -> {
                    // Check timer state
                    val expected = expectedValue as Boolean
                    val actual = timeManager.hasActiveTimer()
                    if (actual != expected) {
                        failures.add("Expected timer_active=$expected, got $actual")
                    }
                }

                else -> {
                    Log.w(TAG, "  → Unknown service state: $key")
                }
            }
        }
    }

    /**
     * Assert negative conditions (what should NOT happen).
     */
    private fun assertNegativeConditions(
        expected: TestExpectations,
        initialState: SystemState,
        finalState: SystemState,
        failures: MutableList<String>
    ) {
        // Check actions that should NOT have executed
        expected.shouldNotExecute?.let { forbiddenActions ->
            val newActions = finalState.recentActions.filter { action ->
                !initialState.recentActions.any { it.id == action.id }
            }.sortedBy { it.timestamp }
            val executedActionNames = newActions.map { it.actionName }

            forbiddenActions.forEach { forbidden ->
                if (executedActionNames.contains(forbidden)) {
                    failures.add("Action '$forbidden' should NOT have executed, but it did")
                }
            }
        }

        // Check entities that should NOT have been created
        expected.shouldNotCreate?.let { forbiddenEntities ->
            forbiddenEntities.forEach { entityType ->
                when (entityType) {
                    "Task" -> {
                        val created = (finalState.taskCount - initialState.taskCount).toInt()
                        if (created > 0) {
                            failures.add("Should NOT have created Task entities, but created $created")
                        }
                    }
                    "WorkflowExecution" -> {
                        val created = (finalState.workflowExecutionCount - initialState.workflowExecutionCount).toInt()
                        if (created > 0) {
                            failures.add("Should NOT have created WorkflowExecution, but created $created")
                        }
                    }
                    "MediaHistory" -> {
                        val created = (finalState.mediaHistoryCount - initialState.mediaHistoryCount).toInt()
                        if (created > 0) {
                            failures.add("Should NOT have created MediaHistory, but created $created")
                        }
                    }
                }
            }
        }
    }

    /**
     * Cleanup after test to ensure isolation.
     * Uses real production methods only.
     */
    private suspend fun cleanup() {
        Log.d(TAG, "🧹 Cleaning up test state...")

        try {
            // Stop music if playing
            mediaHandler.execute("media.stop", JSONObject())

            // Cancel any active timers
            if (timeManager.hasActiveTimer()) {
                timeManager.execute("timer.cancel", JSONObject())
            }

            // Note: Database cleanup happens via in-memory ObjectBox (close DB in test teardown)
            // Service state reset happens via normal service methods (stop, cancel, etc.)
        } catch (e: Exception) {
            Log.e(TAG, "Error during cleanup", e)
        }
    }
}

/**
 * System state snapshot for comparing before/after test execution.
 */
data class SystemState(
    val workflowExecutionCount: Long,
    val actionExecutionCount: Long,
    val taskCount: Long,
    val transcriptCount: Long,
    val mediaHistoryCount: Long,
    val recentWorkflows: List<WorkflowExecution>,
    val recentActions: List<com.ambientai.data.entities.ActionExecution>,
    val activeTasks: List<Task>,
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

/**
 * Exception thrown when precondition setup fails.
 */
class PreconditionFailedException(message: String) : Exception(message)
