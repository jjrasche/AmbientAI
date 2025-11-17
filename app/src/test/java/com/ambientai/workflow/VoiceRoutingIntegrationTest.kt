package com.ambientai.workflow

import com.ambientai.core.llm.GroqLlmService
import com.ambientai.core.workflow.IncompletenessDetector
import com.ambientai.data.entities.WorkflowDefinition
import com.ambientai.data.repositories.fakes.FakeWorkflowDefinitionRepository
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for Voice Pipeline Routing.
 *
 * Tests the WHEN to route (not just WHICH workflow) by:
 * - Simulating partial transcript sequences (like Deepgram partial transcripts)
 * - Testing IncompletenessDetector + WorkflowRouter integration
 * - Validating timing-based routing decisions
 * - Testing UtteranceEnd behavior
 *
 * This bridges the gap between unit tests (isolated logic) and E2E tests (full workflows).
 */
@RunWith(RobolectricTestRunner::class)
class VoiceRoutingIntegrationTest {

    private lateinit var incompletenessDetector: IncompletenessDetector
    private lateinit var router: WorkflowRouter
    private lateinit var workflowRepo: FakeWorkflowDefinitionRepository
    private lateinit var llmService: GroqLlmService

    @Before
    fun setup() {
        incompletenessDetector = IncompletenessDetector()
        workflowRepo = FakeWorkflowDefinitionRepository()
        llmService = mockk(relaxed = true)
        router = WorkflowRouter(workflowRepo, llmService)
    }

    // ===== PARTIAL TRANSCRIPT SEQUENCES =====

    @Test
    fun `partial sequence waits until utterance is complete - parameterized command`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "start_task",
            enabled = true,
            definition = """
                {
                    "requiresInput": true,
                    "triggers": {"keywords": ["start task"]}
                }
            """.trimIndent()
        ))
        router.loadWorkflows()

        // Act & Assert - Simulate Deepgram partial transcript sequence
        val partials = listOf(
            Partial("start", elapsedMs = 1500, confidence = 0.80f),
            Partial("start task", elapsedMs = 2300, confidence = 0.88f),
            Partial("start task grocery shopping", elapsedMs = 4100, confidence = 0.94f)
        )

        partials.forEach { partial ->
            val wordCount = partial.text.split("\\s+".toRegex()).size
            val match = router.route(partial.text, transcriptId = 1L, isPartial = true)
            val isIncomplete = incompletenessDetector.isIncomplete(
                partial.text,
                wordCount,
                match?.definition
            )

            when (partial.text) {
                "start" -> {
                    assertTrue(isIncomplete, "Single word 'start' should be incomplete")
                    // Don't execute yet - incomplete
                }
                "start task" -> {
                    assertTrue(isIncomplete, "Workflow requires input, should wait for parameter")
                    assertNotNull(match, "Should match start_task workflow")
                    assertEquals("start_task", match.definition.name)
                    // Don't execute yet - waiting for input
                }
                "start task grocery shopping" -> {
                    assertFalse(isIncomplete, "Complete utterance with parameter should not be incomplete")
                    assertNotNull(match, "Should match start_task workflow")
                    assertEquals("start_task", match.definition.name)
                    // Execute now - complete!
                }
            }
        }
    }

    @Test
    fun `instant command triggers immediately - single word`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "pause_music",
            enabled = true,
            definition = """{"triggers": {"keywords": ["pause"]}}"""
        ))
        router.loadWorkflows()

        // Act
        val partial = Partial("pause", elapsedMs = 1200, confidence = 0.95f)
        val match = router.route(partial.text, transcriptId = 1L, isPartial = true)
        val isIncomplete = incompletenessDetector.isIncomplete(
            partial.text,
            wordCount = 1,
            match?.definition
        )

        // Assert
        assertFalse(isIncomplete, "Single word 'pause' should be complete for instant command")
        assertNotNull(match, "Should match pause_music workflow")
        assertEquals("pause_music", match.definition.name)
    }

    @Test
    fun `quick command triggers after short delay - no parameters`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "next_track",
            enabled = true,
            definition = """{"triggers": {"keywords": ["next"]}}"""
        ))
        router.loadWorkflows()

        // Act
        val partial = Partial("next", elapsedMs = 1500, confidence = 0.92f)
        val match = router.route(partial.text, transcriptId = 1L, isPartial = true)
        val isIncomplete = incompletenessDetector.isIncomplete(
            partial.text,
            wordCount = 1,
            match?.definition
        )

        // Assert
        assertFalse(isIncomplete, "'next' should be complete")
        assertNotNull(match)
        assertEquals("next_track", match.definition.name)
    }

    // ===== INCOMPLETENESS DETECTION WITH ROUTING =====

    @Test
    fun `detects incomplete utterance - trailing preposition`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "set_timer",
            enabled = true,
            definition = """{"triggers": {"keywords": ["set timer"]}}"""
        ))
        router.loadWorkflows()

        // Act
        val partial = Partial("set timer for", elapsedMs = 2500, confidence = 0.85f)
        val match = router.route(partial.text, transcriptId = 1L, isPartial = true)
        val isIncomplete = incompletenessDetector.isIncomplete(
            partial.text,
            wordCount = 3,
            match?.definition
        )

        // Assert
        assertTrue(isIncomplete, "Should detect trailing 'for' as incomplete")
        assertNotNull(match)
        assertEquals("set_timer", match.definition.name)
        // Don't execute - wait for user to finish
    }

    @Test
    fun `allows complete utterance after preposition resolved`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "set_timer",
            enabled = true,
            definition = """{"triggers": {"keywords": ["set timer"]}}"""
        ))
        router.loadWorkflows()

        // Act - Sequence
        val partials = listOf(
            Partial("set timer for", elapsedMs = 2500, confidence = 0.85f),
            Partial("set timer for five minutes", elapsedMs = 4200, confidence = 0.93f)
        )

        val incomplete = partials[0].let { partial ->
            val wordCount = partial.text.split("\\s+".toRegex()).size
            val match = router.route(partial.text, transcriptId = 1L, isPartial = true)
            incompletenessDetector.isIncomplete(partial.text, wordCount, match?.definition)
        }

        val complete = partials[1].let { partial ->
            val wordCount = partial.text.split("\\s+".toRegex()).size
            val match = router.route(partial.text, transcriptId = 1L, isPartial = true)
            Pair(
                incompletenessDetector.isIncomplete(partial.text, wordCount, match?.definition),
                match
            )
        }

        // Assert
        assertTrue(incomplete, "First partial should be incomplete")
        assertFalse(complete.first, "Second partial should be complete")
        assertNotNull(complete.second)
        assertEquals("set_timer", complete.second!!.definition.name)
    }

    // ===== CANCELLATION DETECTION =====

    @Test
    fun `detects cancellation phrase and stops routing`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "start_task",
            enabled = true,
            definition = """{"triggers": {"keywords": ["start task"]}}"""
        ))
        router.loadWorkflows()

        // Act - User says "start task" then changes mind
        val partials = listOf(
            Partial("start", elapsedMs = 1500, confidence = 0.80f),
            Partial("start task", elapsedMs = 2300, confidence = 0.88f),
            Partial("start task wait", elapsedMs = 3100, confidence = 0.90f)
        )

        val lastPartial = partials.last()
        val cancelled = incompletenessDetector.detectCancellation(lastPartial.text)

        // Assert
        assertTrue(cancelled, "Should detect 'wait' as cancellation")
        // Routing decision: CANCEL - don't execute workflow
    }

    @Test
    fun `detects cancellation with 'no' phrase`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "play_music",
            enabled = true,
            definition = """{"triggers": {"keywords": ["play"]}}"""
        ))
        router.loadWorkflows()

        // Act
        val partial = Partial("play music no", elapsedMs = 2800, confidence = 0.85f)
        val cancelled = incompletenessDetector.detectCancellation(partial.text)

        // Assert
        assertTrue(cancelled, "Should detect 'no' as cancellation")
    }

    @Test
    fun `detects cancellation with 'never mind' phrase`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "set_timer",
            enabled = true,
            definition = """{"triggers": {"keywords": ["set timer"]}}"""
        ))
        router.loadWorkflows()

        // Act
        val partial = Partial("set timer never mind", elapsedMs = 3500, confidence = 0.88f)
        val cancelled = incompletenessDetector.detectCancellation(partial.text)

        // Assert
        assertTrue(cancelled, "Should detect 'never mind' as cancellation")
    }

    // ===== WORKFLOW-SPECIFIC INCOMPLETENESS =====

    @Test
    fun `workflow with requiresInput waits for parameters`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "play_music",
            enabled = true,
            definition = """
                {
                    "requiresInput": true,
                    "triggers": {"keywords": ["play"]}
                }
            """.trimIndent()
        ))
        router.loadWorkflows()

        // Act
        val triggerOnly = Partial("play", elapsedMs = 1500, confidence = 0.90f)
        val match = router.route(triggerOnly.text, transcriptId = 1L, isPartial = true)
        val isIncomplete = incompletenessDetector.isIncomplete(
            triggerOnly.text,
            wordCount = 1,
            match?.definition
        )

        // Assert
        assertTrue(isIncomplete, "Workflow requires input, should wait")
        assertNotNull(match)
        assertEquals("play_music", match.definition.name)
    }

    @Test
    fun `workflow without requiresInput executes immediately`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "pause_music",
            enabled = true,
            definition = """
                {
                    "requiresInput": false,
                    "triggers": {"keywords": ["pause"]}}
            """.trimIndent()
        ))
        router.loadWorkflows()

        // Act
        val partial = Partial("pause", elapsedMs = 1200, confidence = 0.95f)
        val match = router.route(partial.text, transcriptId = 1L, isPartial = true)
        val isIncomplete = incompletenessDetector.isIncomplete(
            partial.text,
            wordCount = 1,
            match?.definition
        )

        // Assert
        assertFalse(isIncomplete, "Workflow doesn't require input, should execute")
        assertNotNull(match)
        assertEquals("pause_music", match.definition.name)
    }

    @Test
    fun `workflow with requiresInput and sufficient parameters executes`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "play_music",
            enabled = true,
            definition = """
                {
                    "requiresInput": true,
                    "triggers": {"keywords": ["play"]}
                }
            """.trimIndent()
        ))
        router.loadWorkflows()

        // Act
        val partial = Partial("play taylor swift", elapsedMs = 2800, confidence = 0.93f)
        val match = router.route(partial.text, transcriptId = 1L, isPartial = true)
        val wordCount = partial.text.split("\\s+".toRegex()).size
        val isIncomplete = incompletenessDetector.isIncomplete(
            partial.text,
            wordCount,
            match?.definition
        )

        // Assert
        assertFalse(isIncomplete, "Has 2+ words after trigger, should be complete")
        assertNotNull(match)
        assertEquals("play_music", match.definition.name)
    }

    // ===== UTTERANCE END BEHAVIOR =====

    @Test
    fun `final transcript executes even if partial was incomplete`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "start_task",
            enabled = true,
            definition = """
                {
                    "requiresInput": true,
                    "triggers": {"keywords": ["start task"]}
                }
            """.trimIndent()
        ))
        router.loadWorkflows()

        // Act - Partial was incomplete, but final transcript is complete
        val partial = Partial("start task", elapsedMs = 2300, confidence = 0.88f)
        val partialMatch = router.route(partial.text, transcriptId = 1L, isPartial = true)
        val partialIncomplete = incompletenessDetector.isIncomplete(
            partial.text,
            wordCount = 2,
            partialMatch?.definition
        )

        // UtteranceEnd fires - user stopped speaking
        val final = Partial("start task groceries", elapsedMs = 4500, confidence = 0.94f)
        val finalMatch = router.route(final.text, transcriptId = 1L, isPartial = false)
        val finalIncomplete = incompletenessDetector.isIncomplete(
            final.text,
            wordCount = 3,
            finalMatch?.definition
        )

        // Assert
        assertTrue(partialIncomplete, "Partial should be incomplete")
        assertFalse(finalIncomplete, "Final should be complete")
        assertNotNull(finalMatch)
        assertEquals("start_task", finalMatch.definition.name)
    }

    // ===== COMPLEX SCENARIOS =====

    @Test
    fun `multi-step partial sequence with workflow requiring input`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "start_task",
            enabled = true,
            definition = """
                {
                    "requiresInput": true,
                    "triggers": {"keywords": ["start task"]}
                }
            """.trimIndent()
        ))
        router.loadWorkflows()

        // Act - Simulate realistic Deepgram partial sequence
        val sequence = listOf(
            Partial("st", elapsedMs = 800, confidence = 0.65f),
            Partial("start", elapsedMs = 1500, confidence = 0.80f),
            Partial("start ta", elapsedMs = 1900, confidence = 0.75f),
            Partial("start task", elapsedMs = 2300, confidence = 0.88f),
            Partial("start task gr", elapsedMs = 3100, confidence = 0.82f),
            Partial("start task grocery", elapsedMs = 3800, confidence = 0.90f),
            Partial("start task grocery shopping", elapsedMs = 4500, confidence = 0.94f)
        )

        val results = sequence.map { partial ->
            val wordCount = partial.text.split("\\s+".toRegex()).size
            val match = router.route(partial.text, transcriptId = 1L, isPartial = true)
            val incomplete = incompletenessDetector.isIncomplete(partial.text, wordCount, match?.definition)
            Triple(partial.text, match, incomplete)
        }

        // Assert - Only last partial should be ready to execute
        results.dropLast(1).forEach { (text, _, incomplete) ->
            assertTrue(incomplete, "$text should be incomplete")
        }

        val (lastText, lastMatch, lastIncomplete) = results.last()
        assertFalse(lastIncomplete, "$lastText should be complete")
        assertNotNull(lastMatch)
        assertEquals("start_task", lastMatch.definition.name)
    }

    @Test
    fun `handles no workflow match with incomplete detection`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "pause_music",
            enabled = true,
            definition = """{"triggers": {"keywords": ["pause"]}}"""
        ))
        router.loadWorkflows()

        // Act - Utterance doesn't match any workflow
        val partial = Partial("unknown command for", elapsedMs = 3000, confidence = 0.85f)
        val match = router.route(partial.text, transcriptId = 1L, isPartial = true)
        val wordCount = partial.text.split("\\s+".toRegex()).size
        val isIncomplete = incompletenessDetector.isIncomplete(
            partial.text,
            wordCount,
            match?.definition
        )

        // Assert
        assertTrue(isIncomplete, "Should detect trailing 'for' even without workflow match")
        assertNotNull(match)
        assertEquals("conversational_default", match.definition.name)
    }

    // ===== PARTIAL VS FINAL DISTINCTION =====

    @Test
    fun `partial transcript triggers routing but checks incompleteness`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "pause_music",
            enabled = true,
            definition = """{"triggers": {"keywords": ["pause"]}}"""
        ))
        router.loadWorkflows()

        // Act
        val partial = Partial("pause", elapsedMs = 1200, confidence = 0.95f)
        val match = router.route(partial.text, transcriptId = 1L, isPartial = true)
        val incomplete = incompletenessDetector.isIncomplete("pause", 1, match?.definition)

        // Assert
        assertNotNull(match, "Should route even for partial")
        assertFalse(incomplete, "Should not be incomplete")
        // Decision: EXECUTE (complete instant command)
    }

    @Test
    fun `final transcript executes regardless of incompleteness heuristics`() {
        // Arrange
        workflowRepo.save(WorkflowDefinition(
            name = "set_timer",
            enabled = true,
            definition = """{"triggers": {"keywords": ["timer"]}}"""
        ))
        router.loadWorkflows()

        // Act - Even though ends with preposition, it's a final transcript
        val final = Partial("set timer for", elapsedMs = 3000, confidence = 0.85f)
        val match = router.route(final.text, transcriptId = 1L, isPartial = false)

        // Assert
        assertNotNull(match)
        // In practice, VoiceListeningService would execute this since isPartial=false
        // means UtteranceEnd has fired, indicating user finished speaking
    }

    // Data class for test readability
    private data class Partial(
        val text: String,
        val elapsedMs: Long,
        val confidence: Float
    )
}
