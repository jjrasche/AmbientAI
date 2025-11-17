package com.ambientai.workflow

import com.ambientai.core.llm.GroqLlmService
import com.ambientai.data.entities.WorkflowDefinition
import com.ambientai.data.repositories.fakes.FakeWorkflowDefinitionRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Unit tests for WorkflowRouter.
 *
 * Tests workflow routing logic:
 * - Trigger phrase matching (exact and partial)
 * - Query cleaning (removing trigger words)
 * - LLM intent extraction fallback
 * - Conversational default creation
 * - Multiple match detection
 */
@RunWith(RobolectricTestRunner::class)
class WorkflowRouterTest {

    private lateinit var workflowRepo: FakeWorkflowDefinitionRepository
    private lateinit var llmService: GroqLlmService
    private lateinit var router: WorkflowRouter

    @Before
    fun setup() {
        workflowRepo = FakeWorkflowDefinitionRepository()
        llmService = mockk(relaxed = true)
        router = WorkflowRouter(workflowRepo, llmService)
    }

    // ===== EXACT TRIGGER MATCHING =====

    @Test
    fun `routes to workflow with exact trigger match`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "pause_music",
            enabled = true,
            definition = """{"triggers": {"keywords": ["pause", "stop music"]}}"""
        ))
        router.loadWorkflows()

        // Act
        val match = router.route("pause", transcriptId = 1L)

        // Assert
        assertNotNull(match)
        assertEquals("pause_music", match.definition.name)
        assertEquals("pause", match.context.matchedTrigger)
    }

    @Test
    fun `routes to workflow with multi-word trigger`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "start_task",
            enabled = true,
            definition = """{"triggers": {"keywords": ["start task"]}}"""
        ))
        router.loadWorkflows()

        // Act
        val match = router.route("start task grocery shopping", transcriptId = 1L)

        // Assert
        assertNotNull(match)
        assertEquals("start_task", match.definition.name)
        assertEquals("start task", match.context.matchedTrigger)
    }

    @Test
    fun `routes to workflow with trigger in middle of utterance`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "play_music",
            enabled = true,
            definition = """{"triggers": {"keywords": ["play music", "play song"]}}"""
        ))
        router.loadWorkflows()

        // Act
        val match = router.route("please play music for me", transcriptId = 1L)

        // Assert
        assertNotNull(match)
        assertEquals("play_music", match.definition.name)
    }

    @Test
    fun `case insensitive trigger matching`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "pause_music",
            enabled = true,
            definition = """{"triggers": {"keywords": ["PAUSE"]}}"""
        ))
        router.loadWorkflows()

        // Act
        val match = router.route("pause", transcriptId = 1L)

        // Assert
        assertNotNull(match)
        assertEquals("pause_music", match.definition.name)
    }

    // ===== TRIGGER ARRAY FORMAT =====

    @Test
    fun `routes with legacy trigger array format`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "next_track",
            enabled = true,
            definition = """{"triggers": ["next", "next track"]}"""
        ))
        router.loadWorkflows()

        // Act
        val match = router.route("next", transcriptId = 1L)

        // Assert
        assertNotNull(match)
        assertEquals("next_track", match.definition.name)
    }

    // ===== QUERY CLEANING =====

    @Test
    fun `cleans trigger words from query - play`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "play_music",
            enabled = true,
            definition = """{"triggers": {"keywords": ["play"]}}"""
        ))
        router.loadWorkflows()

        // Act
        val match = router.route("play taylor swift", transcriptId = 1L)

        // Assert
        assertNotNull(match)
        assertEquals("taylor swift", match.context.variables["transcript"])
    }

    @Test
    fun `cleans trigger words from query - music`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "play_music",
            enabled = true,
            definition = """{"triggers": {"keywords": ["play music"]}}"""
        ))
        router.loadWorkflows()

        // Act
        val match = router.route("play music by radiohead", transcriptId = 1L)

        // Assert
        assertNotNull(match)
        assertEquals("by radiohead", match.context.variables["transcript"])
    }

    @Test
    fun `cleans multiple trigger words`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "play_music",
            enabled = true,
            definition = """{"triggers": {"keywords": ["play song"]}}"""
        ))
        router.loadWorkflows()

        // Act
        val match = router.route("play song the scientist", transcriptId = 1L)

        // Assert
        assertNotNull(match)
        val cleaned = match.context.variables["transcript"] as String
        // Should remove "play" and "song"
        assertEquals("the scientist", cleaned.trim())
    }

    // ===== NO MATCH SCENARIOS =====

    @Test
    fun `creates conversational default when no workflows match`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "pause_music",
            enabled = true,
            definition = """{"triggers": {"keywords": ["pause"]}}"""
        ))
        router.loadWorkflows()

        // Act
        val match = router.route("what is the weather today", transcriptId = 1L)

        // Assert
        assertNotNull(match)
        assertEquals("conversational_default", match.definition.name)
        assertEquals("(default)", match.context.matchedTrigger)
    }

    @Test
    fun `returns null when no workflows exist`() {
        // Arrange
        router.loadWorkflows()

        // Act
        val match = router.route("pause", transcriptId = 1L)

        // Assert
        assertNull(match, "Should return null when no workflows exist")
    }

    // ===== LLM INTENT EXTRACTION =====

    @Test
    fun `attempts LLM intent extraction for short transcripts`() = runTest {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "pause_music",
            enabled = true,
            definition = """{"triggers": {"keywords": ["pause music"]}}"""
        ))
        router.loadWorkflows()

        // Mock LLM to return workflow name
        every { llmService.execute(any(), any()) } returns JSONObject().apply {
            put("success", true)
            put("response", "pause_music")
        }

        // Act - short transcript (< 10 words) with no exact match
        val match = router.route("stop", transcriptId = 1L, isPartial = false)

        // Assert
        assertNotNull(match)
        assertEquals("pause_music", match.definition.name)
        assertEquals("(LLM extracted)", match.context.matchedTrigger)
    }

    @Test
    fun `falls back to conversational default when LLM fails`() = runTest {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "pause_music",
            enabled = true,
            definition = """{"triggers": {"keywords": ["pause"]}}"""
        ))
        router.loadWorkflows()

        // Mock LLM to fail
        every { llmService.execute(any(), any()) } returns JSONObject().apply {
            put("success", false)
            put("error", "LLM error")
        }

        // Act
        val match = router.route("quit", transcriptId = 1L, isPartial = false)

        // Assert
        assertNotNull(match)
        assertEquals("conversational_default", match.definition.name)
    }

    @Test
    fun `falls back to conversational default when LLM returns unknown workflow`() = runTest {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "pause_music",
            enabled = true,
            definition = """{"triggers": {"keywords": ["pause"]}}"""
        ))
        router.loadWorkflows()

        // Mock LLM to return non-existent workflow
        every { llmService.execute(any(), any()) } returns JSONObject().apply {
            put("success", true)
            put("response", "nonexistent_workflow")
        }

        // Act
        val match = router.route("stop", transcriptId = 1L, isPartial = false)

        // Assert
        assertNotNull(match)
        assertEquals("conversational_default", match.definition.name)
    }

    @Test
    fun `skips LLM intent extraction for partial transcripts`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "pause_music",
            enabled = true,
            definition = """{"triggers": {"keywords": ["pause"]}}"""
        ))
        router.loadWorkflows()

        // Act - short transcript but marked as partial
        val match = router.route("stop", transcriptId = 1L, isPartial = true)

        // Assert
        assertNotNull(match)
        assertEquals("conversational_default", match.definition.name)
    }

    @Test
    fun `skips LLM intent extraction for long transcripts`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "pause_music",
            enabled = true,
            definition = """{"triggers": {"keywords": ["pause"]}}"""
        ))
        router.loadWorkflows()

        // Act - long transcript (> 10 words)
        val match = router.route("this is a very long utterance with many words that exceeds threshold", transcriptId = 1L, isPartial = false)

        // Assert
        assertNotNull(match)
        assertEquals("conversational_default", match.definition.name)
    }

    // ===== DISABLED WORKFLOWS =====

    @Test
    fun `ignores disabled workflows`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "pause_music",
            enabled = false,  // Disabled
            definition = """{"triggers": {"keywords": ["pause"]}}"""
        ))
        router.loadWorkflows()

        // Act
        val match = router.route("pause", transcriptId = 1L)

        // Assert
        assertNotNull(match)
        assertEquals("conversational_default", match.definition.name)
    }

    // ===== MULTIPLE WORKFLOWS =====

    @Test
    fun `selects first matching workflow when multiple enabled`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            id = 1L,
            name = "workflow_a",
            enabled = true,
            definition = """{"triggers": {"keywords": ["test"]}}"""
        ))
        workflowRepo.save(WorkflowDefinition(
            id = 2L,
            name = "workflow_b",
            enabled = true,
            definition = """{"triggers": {"keywords": ["other"]}}"""
        ))
        router.loadWorkflows()

        // Act
        val match = router.route("test", transcriptId = 1L)

        // Assert
        assertNotNull(match)
        assertEquals("workflow_a", match.definition.name)
    }

    // ===== TRANSCRIPT ID AND VARIABLES =====

    @Test
    fun `includes transcript ID in context variables`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "pause_music",
            enabled = true,
            definition = """{"triggers": {"keywords": ["pause"]}}"""
        ))
        router.loadWorkflows()

        // Act
        val match = router.route("pause", transcriptId = 42L)

        // Assert
        assertNotNull(match)
        assertEquals(42L, match.context.variables["transcriptId"])
    }

    @Test
    fun `includes full transcript in context`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "start_task",
            enabled = true,
            definition = """{"triggers": {"keywords": ["start task"]}}"""
        ))
        router.loadWorkflows()

        // Act
        val match = router.route("start task grocery shopping", transcriptId = 1L)

        // Assert
        assertNotNull(match)
        assertEquals("start task grocery shopping", match.context.transcript)
    }

    // ===== EDGE CASES =====

    @Test
    fun `handles empty transcript`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "pause_music",
            enabled = true,
            definition = """{"triggers": {"keywords": ["pause"]}}"""
        ))
        router.loadWorkflows()

        // Act
        val match = router.route("", transcriptId = 1L)

        // Assert
        assertNotNull(match)
        assertEquals("conversational_default", match.definition.name)
    }

    @Test
    fun `handles whitespace-only transcript`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "pause_music",
            enabled = true,
            definition = """{"triggers": {"keywords": ["pause"]}}"""
        ))
        router.loadWorkflows()

        // Act
        val match = router.route("   ", transcriptId = 1L)

        // Assert
        assertNotNull(match)
        assertEquals("conversational_default", match.definition.name)
    }

    @Test
    fun `handles malformed workflow definition gracefully`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "broken_workflow",
            enabled = true,
            definition = """{"invalid json"""  // Malformed JSON
        ))
        workflowRepo.save(WorkflowDefinition(
            name = "good_workflow",
            enabled = true,
            definition = """{"triggers": {"keywords": ["test"]}}"""
        ))
        router.loadWorkflows()

        // Act
        val match = router.route("test", transcriptId = 1L)

        // Assert
        assertNotNull(match)
        assertEquals("good_workflow", match.definition.name)
    }

    @Test
    fun `handles workflow with empty triggers array`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "programmatic_workflow",
            enabled = true,
            definition = """{"triggers": []}"""
        ))
        router.loadWorkflows()

        // Act
        val match = router.route("test", transcriptId = 1L)

        // Assert
        assertNotNull(match)
        assertEquals("conversational_default", match.definition.name)
    }

    @Test
    fun `handles workflow with null triggers`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "programmatic_workflow",
            enabled = true,
            definition = """{"triggers": null}"""
        ))
        router.loadWorkflows()

        // Act
        val match = router.route("test", transcriptId = 1L)

        // Assert
        assertNotNull(match)
        assertEquals("conversational_default", match.definition.name)
    }

    // ===== CONVERSATIONAL DEFAULT STRUCTURE =====

    @Test
    fun `conversational default includes LLM and TTS steps`() {
        // Arrange
        router.loadWorkflows()

        // Act
        val match = router.route("what is the capital of france", transcriptId = 1L)

        // Assert
        assertNotNull(match)
        assertEquals("conversational_default", match.definition.name)
        // Verify workflow definition contains expected structure
        val definition = JSONObject(match.definition.definition)
        val steps = definition.getJSONArray("steps")
        assertEquals(2, steps.length())
        assertEquals("llm.prompt", steps.getJSONObject(0).getString("action"))
        assertEquals("tts.speak", steps.getJSONObject(1).getString("action"))
    }

    @Test
    fun `conversational default includes user query in variables`() {
        // Arrange
        router.loadWorkflows()

        // Act
        val match = router.route("tell me a joke", transcriptId = 1L)

        // Assert
        assertNotNull(match)
        assertEquals("tell me a joke", match.context.variables["transcript"])
    }
}
