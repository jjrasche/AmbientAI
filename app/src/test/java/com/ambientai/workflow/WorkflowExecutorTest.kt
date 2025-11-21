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
import com.ambientai.data.entities.ActionExecution
import com.ambientai.data.entities.WorkflowDefinition
import com.ambientai.data.entities.WorkflowExecution
import com.ambientai.data.repositories.IWorkflowDefinitionRepository
import com.ambientai.data.repositories.IWorkflowExecutionRepository
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
 * Unit tests for WorkflowExecutor with mocked dependencies.
 *
 * These tests demonstrate:
 * - Dependency injection enables easy testing with fakes/mocks
 * - Workflow execution logic can be tested without real services
 * - Repository interactions can be verified
 */
@RunWith(RobolectricTestRunner::class)
class WorkflowExecutorTest {

    private lateinit var executionRepo: IWorkflowExecutionRepository
    private lateinit var workflowRepo: IWorkflowDefinitionRepository
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
        // Create mock repositories and services
        executionRepo = mockk(relaxed = true)
        workflowRepo = mockk(relaxed = true)
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

        // Create the executor with mocked dependencies
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
            browser = mockk(relaxed = true)
        )
    }

    @Test
    fun `execute simple workflow with TTS action`() = runTest {
        // Arrange
        val definition = WorkflowDefinition(
            id = 1L,
            name = "test_workflow",
            enabled = true,
            definition = """
                {
                    "triggers": ["test trigger"],
                    "steps": [
                        {
                            "action": "tts.speak",
                            "input": {
                                "text": "Hello world"
                            }
                        }
                    ]
                }
            """.trimIndent()
        )

        val context = WorkflowExecutionContext(
            workflowId = 1L,
            workflowName = "test_workflow",
            transcript = "test trigger",
            matchedTrigger = "test trigger"
        )

        val match = WorkflowMatch(definition, context)

        // Mock execution repo to capture the execution log
        val executionSlot = slot<WorkflowExecution>()
        every { executionRepo.save(capture(executionSlot)) } answers { firstArg() }

        // Mock TTS to return success
        every { tts.execute(any(), any()) } returns JSONObject().apply {
            put("success", true)
        }

        // Act
        val result = executor.execute(match)

        // Assert
        assertTrue(result is WorkflowResult.Success)
        verify { executionRepo.save(any()) }
        verify { tts.execute("tts.speak", any()) }

        // Verify execution was saved with success status
        val savedExecution = executionSlot.captured
        assertEquals(1L, savedExecution.workflowId)
        assertEquals("test_workflow", savedExecution.workflowName)
        assertTrue(savedExecution.success)
    }

    @Test
    fun `execute workflow with variable substitution`() = runTest {
        // Arrange
        val definition = WorkflowDefinition(
            id = 2L,
            name = "variable_test",
            enabled = true,
            definition = """
                {
                    "triggers": ["hello"],
                    "steps": [
                        {
                            "action": "tts.speak",
                            "input": {
                                "text": "${'$'}transcript"
                            }
                        }
                    ]
                }
            """.trimIndent()
        )

        val context = WorkflowExecutionContext(
            workflowId = 2L,
            workflowName = "variable_test",
            transcript = "hello there",
            matchedTrigger = "hello"
        ).apply {
            variables["transcript"] = "hello there"
        }

        val match = WorkflowMatch(definition, context)

        // Mock TTS
        val inputSlot = slot<JSONObject>()
        every { tts.execute(any(), capture(inputSlot)) } returns JSONObject().apply {
            put("success", true)
        }

        // Mock execution repo
        every { executionRepo.save(any()) } answers { firstArg() }

        // Act
        val result = executor.execute(match)

        // Assert
        assertTrue(result is WorkflowResult.Success)

        // Verify the substituted text was passed to TTS
        val ttsInput = inputSlot.captured
        assertEquals("hello there", ttsInput.getString("text"))
    }

    @Test
    fun `execute workflow with conditional step`() = runTest {
        // Arrange
        val definition = WorkflowDefinition(
            id = 3L,
            name = "conditional_test",
            enabled = true,
            definition = """
                {
                    "triggers": ["test"],
                    "steps": [
                        {
                            "action": "control.if",
                            "condition": "${'$'}enabled === true",
                            "then": [
                                {
                                    "action": "tts.speak",
                                    "input": {
                                        "text": "Enabled"
                                    }
                                }
                            ],
                            "else": [
                                {
                                    "action": "tts.speak",
                                    "input": {
                                        "text": "Disabled"
                                    }
                                }
                            ]
                        }
                    ]
                }
            """.trimIndent()
        )

        val context = WorkflowExecutionContext(
            workflowId = 3L,
            workflowName = "conditional_test",
            transcript = "test",
            matchedTrigger = "test"
        ).apply {
            variables["enabled"] = true
        }

        val match = WorkflowMatch(definition, context)

        // Mock TTS
        val inputSlot = slot<JSONObject>()
        every { tts.execute(any(), capture(inputSlot)) } returns JSONObject().apply {
            put("success", true)
        }

        // Mock execution repo
        every { executionRepo.save(any()) } answers { firstArg() }

        // Act
        val result = executor.execute(match)

        // Assert
        assertTrue(result is WorkflowResult.Success)

        // Verify the "then" branch was executed
        assertEquals("Enabled", inputSlot.captured.getString("text"))
    }

    @Test
    fun `execute workflow that fails and records error`() = runTest {
        // Arrange
        val definition = WorkflowDefinition(
            id = 4L,
            name = "failing_workflow",
            enabled = true,
            definition = """
                {
                    "triggers": ["fail"],
                    "steps": [
                        {
                            "action": "tts.speak",
                            "input": {
                                "text": "test"
                            }
                        }
                    ]
                }
            """.trimIndent()
        )

        val context = WorkflowExecutionContext(
            workflowId = 4L,
            workflowName = "failing_workflow",
            transcript = "fail",
            matchedTrigger = "fail"
        )

        val match = WorkflowMatch(definition, context)

        // Mock TTS to throw an exception
        every { tts.execute(any(), any()) } throws RuntimeException("TTS failed")

        // Mock execution repo
        val executionSlot = slot<WorkflowExecution>()
        every { executionRepo.save(capture(executionSlot)) } answers { firstArg() }

        // Act
        val result = executor.execute(match)

        // Assert
        assertTrue(result is WorkflowResult.Failure)
        assertEquals("TTS failed", (result as WorkflowResult.Failure).error)

        // Verify execution was saved with error
        val savedExecution = executionSlot.captured
        assertEquals(false, savedExecution.success)
        assertEquals("TTS failed", savedExecution.errorMessage)
    }

    @Test
    fun `executeById retrieves workflow and executes it`() = runTest {
        // Arrange
        val definition = WorkflowDefinition(
            id = 5L,
            name = "programmatic_test",
            enabled = true,
            definition = """
                {
                    "triggers": [],
                    "steps": [
                        {
                            "action": "tts.speak",
                            "input": {
                                "text": "${'$'}message"
                            }
                        }
                    ]
                }
            """.trimIndent()
        )

        // Mock workflow repo to return the definition
        every { workflowRepo.getById(5L) } returns definition

        // Mock TTS
        val inputSlot = slot<JSONObject>()
        every { tts.execute(any(), capture(inputSlot)) } returns JSONObject().apply {
            put("success", true)
        }

        // Mock execution repo
        every { executionRepo.save(any()) } answers { firstArg() }

        // Act
        val result = executor.executeById(5L, mapOf("message" to "Hello from test"))

        // Assert
        assertTrue(result is WorkflowResult.Success)

        // Verify the message variable was substituted
        assertEquals("Hello from test", inputSlot.captured.getString("text"))
    }

    @Test
    fun `loadCompletionTriggers parses workflow triggers correctly`() = runTest {
        // Arrange
        val workflows = listOf(
            WorkflowDefinition(
                id = 1L,
                name = "workflow_a",
                enabled = true,
                definition = """
                    {
                        "triggers": [],
                        "steps": []
                    }
                """.trimIndent()
            ),
            WorkflowDefinition(
                id = 2L,
                name = "workflow_b",
                enabled = true,
                definition = """
                    {
                        "triggers": {
                            "onWorkflowComplete": ["workflow_a"]
                        },
                        "steps": []
                    }
                """.trimIndent()
            )
        )

        every { workflowRepo.getEnabled() } returns workflows

        // Act
        executor.loadCompletionTriggers()

        // Assert
        // This test verifies the method doesn't crash - actual trigger firing
        // would require more complex test setup
        verify { workflowRepo.getEnabled() }
    }
}
