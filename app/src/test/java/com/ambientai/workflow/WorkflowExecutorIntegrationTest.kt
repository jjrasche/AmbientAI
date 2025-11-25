package com.ambientai.workflow

import com.ambientai.core.llm.GroqLlmService
import com.ambientai.core.log.LogManager
import com.ambientai.core.music.MusicPlayerHandler
import com.ambientai.core.music.MusicScanner
import com.ambientai.core.search.SearchService
import com.ambientai.core.task.TaskManager
import com.ambientai.core.time.TimeManager
import com.ambientai.core.tts.TextToSpeechService
import com.ambientai.core.ui.UiService
import com.ambientai.core.workflow.actions.WorkflowActionHandler
import com.ambientai.data.entities.WorkflowDefinition
import com.ambientai.data.repositories.fakes.FakeWorkflowDefinitionRepository
import com.ambientai.data.repositories.fakes.FakeWorkflowExecutionRepository
import io.mockk.*
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for WorkflowExecutor using fake repositories.
 *
 * These tests demonstrate the full workflow execution flow with
 * in-memory repository implementations, showing how DI enables
 * easy testing without external dependencies.
 *
 * BENEFITS OF USING FAKES:
 * - No database setup required
 * - Fast test execution
 * - Easy to verify repository state
 * - Tests remain deterministic
 */
@RunWith(RobolectricTestRunner::class)
class WorkflowExecutorIntegrationTest {

    private lateinit var executionRepo: FakeWorkflowExecutionRepository
    private lateinit var workflowRepo: FakeWorkflowDefinitionRepository
    private lateinit var tts: TextToSpeechService
    private lateinit var tasks: TaskManager
    private lateinit var llm: GroqLlmService
    private lateinit var search: SearchService
    private lateinit var logs: LogManager
    private lateinit var time: TimeManager
    private lateinit var workflowActions: WorkflowActionHandler
    private lateinit var musicPlayer: MusicPlayerHandler
    private lateinit var musicScanner: MusicScanner
    private lateinit var ui: UiService
    private lateinit var mediaHandler: com.ambientai.core.media.MediaHandler
    private lateinit var executor: WorkflowExecutor

    @Before
    fun setup() {
        // Use fake repositories for realistic behavior
        executionRepo = FakeWorkflowExecutionRepository()
        workflowRepo = FakeWorkflowDefinitionRepository()

        // Mock services that interact with external APIs
        tts = mockk(relaxed = true)
        tasks = mockk(relaxed = true)
        llm = mockk(relaxed = true)
        search = mockk(relaxed = true)
        logs = mockk(relaxed = true)
        time = mockk(relaxed = true)
        workflowActions = mockk(relaxed = true)
        musicPlayer = mockk(relaxed = true)
        musicScanner = mockk(relaxed = true)
        ui = mockk(relaxed = true)
        mediaHandler = mockk(relaxed = true)

        // Create executor with mixed fake/mock dependencies
        executor = WorkflowExecutor(
            executionRepo = executionRepo,
            workflowRepo = workflowRepo,
            tts = tts,
            tasks = tasks,
            llm = llm,
            search = search,
            logs = logs,
            time = time,
            workflowActions = workflowActions,
            ui = ui,
            mediaHandler = mediaHandler,
            browser = mockk(relaxed = true),
            subscriptionHandler = mockk(relaxed = true),
            slotFilling = mockk(relaxed = true)
        )

        // Clear repos before each test
        executionRepo.clear()
        workflowRepo.clear()
    }

    @Test
    fun `execute workflow and verify execution log is saved`() = runTest {
        // Arrange
        val workflow = WorkflowDefinition(
            id = 0L,
            name = "greeting_workflow",
            enabled = true,
            definition = """
                {
                    "triggers": ["hello"],
                    "steps": [
                        {
                            "action": "tts.speak",
                            "input": {
                                "text": "Hello, how can I help you?"
                            }
                        }
                    ]
                }
            """.trimIndent()
        )

        workflowRepo.save(workflow)

        val context = WorkflowExecutionContext(
            workflowId = workflow.id,
            workflowName = workflow.name,
            transcript = "hello",
            matchedTrigger = "hello"
        )

        val match = WorkflowMatch(workflow, context)

        // Mock TTS to return success
        every { tts.execute(any(), any()) } returns JSONObject().apply {
            put("success", true)
        }

        // Act
        val result = executor.execute(match)

        // Assert
        assertTrue(result is WorkflowResult.Success)

        // Verify execution was logged in the fake repository
        assertEquals(1, executionRepo.getExecutionCount())

        val savedExecution = executionRepo.getAll().first()
        assertEquals(workflow.id, savedExecution.workflowId)
        assertEquals("greeting_workflow", savedExecution.workflowName)
        assertTrue(savedExecution.success)
    }

    @Test
    fun `execute multiple workflows and verify all executions are logged`() = runTest {
        // Arrange
        val workflow1 = workflowRepo.save(WorkflowDefinition(
            id = 0L,
            name = "workflow_1",
            enabled = true,
            definition = """{"triggers": [], "steps": [{"action": "tts.speak", "input": {"text": "One"}}]}"""
        ))

        val workflow2 = workflowRepo.save(WorkflowDefinition(
            id = 0L,
            name = "workflow_2",
            enabled = true,
            definition = """{"triggers": [], "steps": [{"action": "tts.speak", "input": {"text": "Two"}}]}"""
        ))

        // Mock TTS
        every { tts.execute(any(), any()) } returns JSONObject().apply {
            put("success", true)
        }

        // Act
        executor.execute(WorkflowMatch(workflow1, WorkflowExecutionContext(workflow1.id, workflow1.name, "", "")))
        executor.execute(WorkflowMatch(workflow2, WorkflowExecutionContext(workflow2.id, workflow2.name, "", "")))

        // Assert
        assertEquals(2, executionRepo.getExecutionCount())
        assertEquals(2, executionRepo.getSuccessfulExecutions().size)
        assertEquals(0, executionRepo.getFailedExecutions().size)
    }

    @Test
    fun `failed workflow execution is logged with error`() = runTest {
        // Arrange
        val workflow = workflowRepo.save(WorkflowDefinition(
            id = 0L,
            name = "failing_workflow",
            enabled = true,
            definition = """{"triggers": [], "steps": [{"action": "tts.speak", "input": {"text": "test"}}]}"""
        ))

        val context = WorkflowExecutionContext(workflow.id, workflow.name, "", "")
        val match = WorkflowMatch(workflow, context)

        // Mock TTS to fail
        every { tts.execute(any(), any()) } throws RuntimeException("Service unavailable")

        // Act
        val result = executor.execute(match)

        // Assert
        assertTrue(result is WorkflowResult.Failure)
        assertEquals("Service unavailable", (result as WorkflowResult.Failure).error)

        // Verify failure was logged
        assertEquals(1, executionRepo.getExecutionCount())
        assertEquals(0, executionRepo.getSuccessfulExecutions().size)
        assertEquals(1, executionRepo.getFailedExecutions().size)

        val failedExecution = executionRepo.getFailedExecutions().first()
        assertEquals("Service unavailable", failedExecution.errorMessage)
    }

    @Test
    fun `executeById retrieves workflow from fake repo`() = runTest {
        // Arrange
        val workflow = workflowRepo.save(WorkflowDefinition(
            id = 0L,
            name = "test_workflow",
            enabled = true,
            definition = """
                {
                    "triggers": [],
                    "steps": [
                        {
                            "action": "tts.speak",
                            "input": {
                                "text": "${'$'}customMessage"
                            }
                        }
                    ]
                }
            """.trimIndent()
        ))

        // Mock TTS
        val inputSlot = slot<JSONObject>()
        every { tts.execute(any(), capture(inputSlot)) } returns JSONObject().apply {
            put("success", true)
        }

        // Act
        val result = executor.executeById(
            workflow.id,
            mapOf("customMessage" to "Hello from integration test")
        )

        // Assert
        assertTrue(result is WorkflowResult.Success)
        assertEquals("Hello from integration test", inputSlot.captured.getString("text"))

        // Verify execution was logged
        assertEquals(1, executionRepo.getExecutionCount())
    }

    @Test
    fun `loadCompletionTriggers reads from fake workflow repo`() = runTest {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            id = 0L,
            name = "base_workflow",
            enabled = true,
            definition = """{"triggers": [], "steps": []}"""
        ))

        workflowRepo.save(WorkflowDefinition(
            id = 0L,
            name = "dependent_workflow",
            enabled = true,
            definition = """
                {
                    "triggers": {
                        "onWorkflowComplete": ["base_workflow"]
                    },
                    "steps": []
                }
            """.trimIndent()
        ))

        // Act
        executor.loadCompletionTriggers()

        // Assert - verify it doesn't crash and reads from repo
        assertEquals(2, workflowRepo.count())
        assertEquals(2, workflowRepo.getEnabled().size)
    }

    @Test
    fun `workflow with output variable stores result in context`() = runTest {
        // Arrange
        val workflow = workflowRepo.save(WorkflowDefinition(
            id = 0L,
            name = "output_test",
            enabled = true,
            definition = """
                {
                    "triggers": [],
                    "steps": [
                        {
                            "action": "llm.prompt",
                            "input": {
                                "systemPrompt": "You are a helpful assistant",
                                "userPrompt": "Say hello",
                                "temperature": 0.7,
                                "maxTokens": 50
                            },
                            "output": "llmResponse"
                        },
                        {
                            "action": "tts.speak",
                            "input": {
                                "text": "${'$'}llmResponse.response"
                            }
                        }
                    ]
                }
            """.trimIndent()
        ))

        val context = WorkflowExecutionContext(workflow.id, workflow.name, "", "")
        val match = WorkflowMatch(workflow, context)

        // Mock LLM to return a response
        every { llm.execute(any(), any()) } returns JSONObject().apply {
            put("success", true)
            put("response", "Hello there!")
        }

        // Mock TTS
        val ttsInputSlot = slot<JSONObject>()
        every { tts.execute(any(), capture(ttsInputSlot)) } returns JSONObject().apply {
            put("success", true)
        }

        // Act
        val result = executor.execute(match)

        // Assert
        assertTrue(result is WorkflowResult.Success)

        // Verify the LLM response was passed to TTS
        assertEquals("Hello there!", ttsInputSlot.captured.getString("text"))
    }
}
