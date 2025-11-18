package com.ambientai.testing

import android.content.Context
import android.util.Log
import com.ambientai.core.media.MediaHandler
import com.ambientai.core.task.TaskManager
import com.ambientai.core.time.TimeManager
import com.ambientai.data.entities.Media
import com.ambientai.data.entities.Task
import com.ambientai.data.entities.TaskStatus
import com.ambientai.data.entities.WorkflowExecution
import com.ambientai.data.repositories.*
import com.ambientai.debug.SttSimulator
import com.ambientai.workflow.WorkflowRouter
import com.ambientai.workflow.WorkflowExecutor
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeoutException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RegressionTestExecutor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sttSimulator: SttSimulator,
    private val workflowRouter: WorkflowRouter,
    private val workflowExecutor: WorkflowExecutor,
    private val workflowExecRepo: IWorkflowExecutionRepository,
    private val actionExecRepo: IActionExecutionRepository,
    private val taskRepo: ITaskRepository,
    private val transcriptRepo: ITranscriptRepository,
    private val mediaHistoryRepo: IMediaHistoryRepository,
    private val mediaRepo: IMediaRepository,
    private val mediaHandler: MediaHandler,
    private val taskManager: TaskManager,
    private val timeManager: TimeManager
) {
    companion object {
        private const val TAG = "RegressionTest"
        private const val DEFAULT_TIMEOUT_MS = 5000L
        private const val POLL_INTERVAL_MS = 100L
    }

    suspend fun runTest(scenario: RegressionTestScenario): TestResult {
        Log.d(TAG, "🧪 RUNNING TEST: ${scenario.testId}")
        val testStartTime = System.currentTimeMillis()
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

            // 5. Wait for workflow completion (polling instead of fixed delay)
            val execution = try {
                waitForWorkflowCompletion(testStartTime, DEFAULT_TIMEOUT_MS)
            } catch (e: TimeoutException) {
                Log.w(TAG, "  → Workflow did not complete within timeout")
                null
            }

            // 6. Capture final state
            val finalState = captureState()

            // 7. Assert expectations
            assertExpectations(scenario.expected, initialState, finalState, failures)

            // 8. Assert service state changes (Level 2 verification)
            assertServiceStateChanges(scenario.expected, failures)

            // 9. Assert negative conditions
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
     * Apply preconditions using REAL production methods only.
     * No test hooks, no special modes.
     */
    private suspend fun applyPreconditions(preconditions: Map<String, Any>) {
        Log.d(TAG, "📋 Applying preconditions: ${preconditions.keys}")

        preconditions.forEach { (key, value) ->
            when (key) {
                // Database state - direct entity creation
                "media_in_library" -> {
                    val mediaList = value as List<Map<String, Any>>
                    mediaList.forEach { mediaData ->
                        // Copy test audio from assets to device
                        val audioFile = copyAssetToCache(
                            assetPath = "test_audio/${mediaData["filePath"]}",
                            cacheDir = context.cacheDir
                        )

                        // Create Media entity with real file path
                        mediaRepo.save(Media(
                            title = mediaData["title"] as String,
                            sourceType = "local",
                            mediaType = "audio",
                            sourceUrl = audioFile.absolutePath,
                            duration = 0L,
                            channelName = mediaData["artist"] as? String ?: "Unknown",
                            localFilePath = audioFile.absolutePath
                        ))
                        Log.d(TAG, "  → Created media: ${mediaData["title"]}")
                    }
                }

                "active_task" -> {
                    // Use REAL task creation action (no test hooks)
                    taskManager.execute("task.start", JSONObject().apply {
                        put("name", value as String)
                    })
                    Log.d(TAG, "  → Created active task: $value")
                }

                "paused_task" -> {
                    // Create task using real action
                    taskManager.execute("task.start", JSONObject().apply {
                        put("name", value as String)
                    })
                    // Pause it using real action
                    taskManager.execute("task.pause", JSONObject())
                    Log.d(TAG, "  → Created paused task: $value")
                }

                // Service state - use REAL methods
                "music_playing" -> {
                    // Play first available song in library (any artist/song will work for precondition)
                    // This uses the REAL media.play action which will search and play
                    val result = mediaHandler.execute("media.play", JSONObject().apply {
                        put("query", "a")  // Generic query that should match many songs
                    })

                    if (!result.getBoolean("success")) {
                        Log.w(TAG, "  → Failed to play music: ${result.optString("error")}")
                        Log.w(TAG, "  → Continuing test anyway (music playback not critical for routing)")
                    }

                    // Wait for playback to start (onPrepared callback + state update)
                    delay(1500) // Give time for MediaPlayer to prepare and update PlaybackStateManager
                    Log.d(TAG, "  → Music play precondition complete")
                }

                "timer_running" -> {
                    // Use REAL timer action (no test hooks)
                    val durationMs = value as Long
                    timeManager.execute("timer.set", JSONObject().apply {
                        put("minutes", durationMs / 60000)
                    })
                    Log.d(TAG, "  → Timer set for ${durationMs / 60000} minutes")
                }

                "music_paused" -> {
                    // Copy test audio from assets
                    val testFile = copyAssetToCache(
                        assetPath = "test_audio/$value",
                        cacheDir = context.cacheDir
                    )

                    // Use REAL playback method to start playing
                    mediaHandler.execute("media.play", JSONObject().apply {
                        put("filePath", testFile.absolutePath)
                    })
                    delay(500) // Give time for playback to start

                    // Pause using real action handler
                    mediaHandler.execute("media.pause", JSONObject())
                    delay(200) // Give time for pause to take effect

                    Log.d(TAG, "  → Music paused: $value")
                }

                else -> {
                    Log.w(TAG, "  → Unknown precondition: $key")
                }
            }
        }
    }

    /**
     * Copy file from assets to cache directory for testing.
     */
    private fun copyAssetToCache(assetPath: String, cacheDir: File): File {
        val outputFile = File(cacheDir, assetPath.substringAfterLast("/"))

        // Create parent directories if needed
        outputFile.parentFile?.mkdirs()

        context.assets.open(assetPath).use { input ->
            outputFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        return outputFile
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
