package com.ambientai.workflow

import com.ambientai.core.workflow.IncompletenessDetector
import com.ambientai.data.entities.WorkflowDefinition
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for IncompletenessDetector.
 *
 * Tests incompleteness detection heuristics:
 * - Trailing prepositions/conjunctions/articles
 * - Very short words
 * - Workflow-specific incompleteness (requiresInput flag)
 * - Cancellation phrase detection
 */
@RunWith(RobolectricTestRunner::class)
class IncompletenessDetectorTest {

    private lateinit var detector: IncompletenessDetector

    @Before
    fun setup() {
        detector = IncompletenessDetector()
    }

    // ===== TRAILING PREPOSITION TESTS =====

    @Test
    fun `detects trailing preposition - at`() {
        val result = detector.isIncomplete("look at", wordCount = 2)
        assertTrue(result, "Should detect trailing 'at' as incomplete")
    }

    @Test
    fun `detects trailing preposition - in`() {
        val result = detector.isIncomplete("place in", wordCount = 2)
        assertTrue(result, "Should detect trailing 'in' as incomplete")
    }

    @Test
    fun `detects trailing preposition - for`() {
        val result = detector.isIncomplete("set timer for", wordCount = 3)
        assertTrue(result, "Should detect trailing 'for' as incomplete")
    }

    @Test
    fun `detects trailing preposition - to`() {
        val result = detector.isIncomplete("go to", wordCount = 2)
        assertTrue(result, "Should detect trailing 'to' as incomplete")
    }

    @Test
    fun `detects trailing preposition - with`() {
        val result = detector.isIncomplete("play song with", wordCount = 3)
        assertTrue(result, "Should detect trailing 'with' as incomplete")
    }

    // ===== TRAILING CONJUNCTION TESTS =====

    @Test
    fun `detects trailing conjunction - and`() {
        val result = detector.isIncomplete("pause music and", wordCount = 3)
        assertTrue(result, "Should detect trailing 'and' as incomplete")
    }

    @Test
    fun `detects trailing conjunction - but`() {
        val result = detector.isIncomplete("start task but", wordCount = 3)
        assertTrue(result, "Should detect trailing 'but' as incomplete")
    }

    @Test
    fun `detects trailing conjunction - or`() {
        val result = detector.isIncomplete("play this or", wordCount = 3)
        assertTrue(result, "Should detect trailing 'or' as incomplete")
    }

    // ===== TRAILING ARTICLE TESTS =====

    @Test
    fun `detects trailing article - the`() {
        val result = detector.isIncomplete("play the", wordCount = 2)
        assertTrue(result, "Should detect trailing 'the' as incomplete")
    }

    @Test
    fun `detects trailing article - a`() {
        val result = detector.isIncomplete("set a", wordCount = 2)
        assertTrue(result, "Should detect trailing 'a' as incomplete")
    }

    @Test
    fun `detects trailing article - an`() {
        val result = detector.isIncomplete("create an", wordCount = 2)
        assertTrue(result, "Should detect trailing 'an' as incomplete")
    }

    // ===== VERY SHORT WORD TESTS =====

    @Test
    fun `detects very short single word - one letter`() {
        val result = detector.isIncomplete("a", wordCount = 1)
        assertTrue(result, "Single letter should be incomplete")
    }

    @Test
    fun `detects very short single word - two letters`() {
        val result = detector.isIncomplete("go", wordCount = 1)
        assertTrue(result, "Two-letter single word should be incomplete")
    }

    @Test
    fun `detects very short single word - three letters`() {
        val result = detector.isIncomplete("set", wordCount = 1)
        assertTrue(result, "Three-letter single word should be incomplete")
    }

    @Test
    fun `allows short single word - four letters`() {
        val result = detector.isIncomplete("stop", wordCount = 1)
        assertFalse(result, "Four-letter single word should be complete")
    }

    @Test
    fun `allows short single word - pause`() {
        val result = detector.isIncomplete("pause", wordCount = 1)
        assertFalse(result, "'pause' should be complete")
    }

    @Test
    fun `allows short single word - next`() {
        val result = detector.isIncomplete("next", wordCount = 1)
        assertFalse(result, "'next' should be complete")
    }

    // ===== WORKFLOW-SPECIFIC INCOMPLETENESS TESTS =====

    @Test
    fun `detects workflow requiring input - trigger only`() {
        val workflow = WorkflowDefinition(
            id = 1L,
            name = "start_task",
            enabled = true,
            definition = """
                {
                    "requiresInput": true,
                    "triggers": {
                        "keywords": ["start task"]
                    }
                }
            """.trimIndent()
        )

        val result = detector.isIncomplete("start task", wordCount = 2, matchedWorkflow = workflow)
        assertTrue(result, "Workflow requires input but only has trigger phrase")
    }

    @Test
    fun `allows workflow requiring input - has parameter`() {
        val workflow = WorkflowDefinition(
            id = 1L,
            name = "start_task",
            enabled = true,
            definition = """
                {
                    "requiresInput": true,
                    "triggers": {
                        "keywords": ["start task"]
                    }
                }
            """.trimIndent()
        )

        val result = detector.isIncomplete("start task grocery shopping", wordCount = 4, matchedWorkflow = workflow)
        assertFalse(result, "Workflow has input parameter after trigger")
    }

    @Test
    fun `detects workflow requiring input - only one word after trigger`() {
        val workflow = WorkflowDefinition(
            id = 1L,
            name = "start_task",
            enabled = true,
            definition = """
                {
                    "requiresInput": true,
                    "triggers": {
                        "keywords": ["start task"]
                    }
                }
            """.trimIndent()
        )

        val result = detector.isIncomplete("start task work", wordCount = 3, matchedWorkflow = workflow)
        assertTrue(result, "Only one word after trigger is considered incomplete for requiresInput workflows")
    }

    @Test
    fun `allows workflow not requiring input - trigger only`() {
        val workflow = WorkflowDefinition(
            id = 1L,
            name = "pause_music",
            enabled = true,
            definition = """
                {
                    "requiresInput": false,
                    "triggers": {
                        "keywords": ["pause"]
                    }
                }
            """.trimIndent()
        )

        val result = detector.isIncomplete("pause", wordCount = 1, matchedWorkflow = workflow)
        assertFalse(result, "Workflow doesn't require input, so trigger only is complete")
    }

    @Test
    fun `detects partial trigger phrase`() {
        val workflow = WorkflowDefinition(
            id = 1L,
            name = "start_task",
            enabled = true,
            definition = """
                {
                    "requiresInput": true,
                    "triggers": {
                        "keywords": ["start task"]
                    }
                }
            """.trimIndent()
        )

        val result = detector.isIncomplete("start", wordCount = 1, matchedWorkflow = workflow)
        assertTrue(result, "Partial trigger phrase should be incomplete")
    }

    // ===== COMPLETE UTTERANCE TESTS =====

    @Test
    fun `allows complete utterance - no trailing words`() {
        val result = detector.isIncomplete("pause music", wordCount = 2)
        assertFalse(result, "Complete utterance should not be incomplete")
    }

    @Test
    fun `allows complete utterance - ends with noun`() {
        val result = detector.isIncomplete("start task groceries", wordCount = 3)
        assertFalse(result, "Utterance ending with noun should be complete")
    }

    @Test
    fun `allows complete utterance - instant command`() {
        val result = detector.isIncomplete("pause", wordCount = 1)
        assertFalse(result, "Instant command should be complete")
    }

    @Test
    fun `allows complete utterance - question`() {
        val result = detector.isIncomplete("what time is it", wordCount = 4)
        assertFalse(result, "Question should be complete")
    }

    // ===== BLANK/EMPTY TESTS =====

    @Test
    fun `detects empty string as incomplete`() {
        val result = detector.isIncomplete("", wordCount = 0)
        assertTrue(result, "Empty string should be incomplete")
    }

    @Test
    fun `detects blank string as incomplete`() {
        val result = detector.isIncomplete("   ", wordCount = 0)
        assertTrue(result, "Blank string should be incomplete")
    }

    // ===== CANCELLATION DETECTION TESTS =====

    @Test
    fun `detects cancellation - wait`() {
        val result = detector.detectCancellation("start task wait")
        assertTrue(result, "Should detect 'wait' as cancellation")
    }

    @Test
    fun `detects cancellation - no`() {
        val result = detector.detectCancellation("play music no")
        assertTrue(result, "Should detect 'no' as cancellation")
    }

    @Test
    fun `detects cancellation - cancel`() {
        val result = detector.detectCancellation("set timer cancel")
        assertTrue(result, "Should detect 'cancel' as cancellation")
    }

    @Test
    fun `detects cancellation - never mind`() {
        val result = detector.detectCancellation("start task never mind")
        assertTrue(result, "Should detect 'never mind' as cancellation")
    }

    @Test
    fun `detects cancellation - actually`() {
        val result = detector.detectCancellation("play song actually")
        assertTrue(result, "Should detect 'actually' as cancellation")
    }

    @Test
    fun `no cancellation - normal utterance`() {
        val result = detector.detectCancellation("start task grocery shopping")
        assertFalse(result, "Normal utterance should not have cancellation")
    }

    @Test
    fun `no cancellation - empty string`() {
        val result = detector.detectCancellation("")
        assertFalse(result, "Empty string should not have cancellation")
    }

    // ===== EDGE CASES =====

    @Test
    fun `handles mixed case`() {
        val result = detector.isIncomplete("Play The", wordCount = 2)
        assertTrue(result, "Should detect trailing article regardless of case")
    }

    @Test
    fun `handles extra whitespace`() {
        val result = detector.isIncomplete("  start  task  ", wordCount = 2)
        assertFalse(result, "Should handle extra whitespace correctly")
    }

    @Test
    fun `handles multiple prepositions - only last matters`() {
        val result = detector.isIncomplete("look in the box for", wordCount = 5)
        assertTrue(result, "Should detect last word 'for' as incomplete")
    }

    @Test
    fun `allows preposition in middle of utterance`() {
        val result = detector.isIncomplete("set timer for five minutes", wordCount = 5)
        assertFalse(result, "Preposition in middle is fine if utterance ends properly")
    }
}
