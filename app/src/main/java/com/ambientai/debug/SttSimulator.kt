package com.ambientai.debug

import android.util.Log
import com.ambientai.core.VoiceListeningService
import com.ambientai.core.workflow.IncompletenessDetector
import com.ambientai.core.workflow.RoutingConfig
import com.ambientai.core.workflow.CommandTier
import com.ambientai.workflow.WorkflowRouter
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Simulates STT (Deepgram) behavior for testing routing logic without audio input.
 * Allows testing of partial transcripts, UtteranceEnd events, and routing decisions.
 */
@Singleton
class SttSimulator @Inject constructor(
    private val workflowRouter: WorkflowRouter
) {
    companion object {
        private const val TAG = "SttSimulator"
    }

    private val incompletenessDetector = IncompletenessDetector()

    /**
     * Test a single partial transcript and return what the routing logic would decide.
     * Does NOT execute workflows - just analyzes.
     */
    fun testPartialRouting(
        text: String,
        elapsedMs: Long,
        confidence: Float = 0.85f
    ): RoutingTestResult {
        Log.d(TAG, "🧪 TESTING PARTIAL: \"$text\" at ${elapsedMs}ms (confidence: $confidence)")

        val wordCount = text.split("\\s+".toRegex()).size
        val decisions = mutableListOf<String>()

        // Stage 1: Check if too early
        if (elapsedMs < RoutingConfig.MIN_ELAPSED_BEFORE_ROUTING_MS) {
            decisions.add("TOO_EARLY: ${elapsedMs}ms < ${RoutingConfig.MIN_ELAPSED_BEFORE_ROUTING_MS}ms")
            return RoutingTestResult(
                text = text,
                elapsedMs = elapsedMs,
                wordCount = wordCount,
                decision = "WAIT",
                reason = "Too early to route",
                wouldTrigger = false,
                matchedWorkflow = null,
                tier = classifyTier(wordCount, elapsedMs),
                decisions = decisions,
                confidence = confidence
            )
        }
        decisions.add("TIMING_OK: ${elapsedMs}ms >= ${RoutingConfig.MIN_ELAPSED_BEFORE_ROUTING_MS}ms")

        // Stage 2: Check for cancellation
        if (incompletenessDetector.detectCancellation(text)) {
            decisions.add("CANCELLATION_DETECTED")
            return RoutingTestResult(
                text = text,
                elapsedMs = elapsedMs,
                wordCount = wordCount,
                decision = "CANCEL",
                reason = "Cancellation phrase detected",
                wouldTrigger = false,
                matchedWorkflow = null,
                tier = classifyTier(wordCount, elapsedMs),
                decisions = decisions,
                confidence = confidence
            )
        }
        decisions.add("NO_CANCELLATION")

        // Stage 3: Try to match workflow first (needed for context-aware incompleteness detection)
        val match = try {
            workflowRouter.route(text, -1, isPartial = true)
        } catch (e: Exception) {
            decisions.add("ROUTING_ERROR: ${e.message}")
            null
        }

        val wouldTrigger = match != null && match.definition.name != "conversational_default"

        if (wouldTrigger) {
            decisions.add("WORKFLOW_MATCHED: ${match?.definition?.name}")
        } else if (match != null) {
            decisions.add("CONVERSATIONAL_DEFAULT")
        } else {
            decisions.add("NO_WORKFLOW_MATCH")
        }

        // Stage 4: Check for incompleteness (with workflow context)
        if (incompletenessDetector.isIncomplete(text, wordCount, match?.definition)) {
            decisions.add("INCOMPLETE_UTTERANCE")
            return RoutingTestResult(
                text = text,
                elapsedMs = elapsedMs,
                wordCount = wordCount,
                decision = "WAIT",
                reason = "Utterance appears incomplete",
                wouldTrigger = false,
                matchedWorkflow = match?.definition?.name,
                tier = classifyTier(wordCount, elapsedMs),
                decisions = decisions,
                confidence = confidence
            )
        }
        decisions.add("UTTERANCE_COMPLETE")

        // Stage 5: Classify command tier
        val tier = classifyTier(wordCount, elapsedMs)
        decisions.add("CLASSIFIED_AS: $tier")

        return RoutingTestResult(
            text = text,
            elapsedMs = elapsedMs,
            wordCount = wordCount,
            decision = if (wouldTrigger) "EXECUTE" else if (match != null) "CONVERSATIONAL" else "NO_MATCH",
            reason = decisions.last(),
            wouldTrigger = wouldTrigger,
            matchedWorkflow = match?.definition?.name,
            tier = tier,
            decisions = decisions,
            confidence = confidence
        )
    }

    /**
     * Simulate a sequence of partial transcripts as they would arrive from Deepgram.
     * Returns results for each partial showing how routing evolves.
     */
    fun testSequence(partials: List<PartialTranscript>): SequenceTestResult {
        Log.d(TAG, "🧪 TESTING SEQUENCE: ${partials.size} partials")

        val results = mutableListOf<RoutingTestResult>()
        var triggeredAt: Int? = null

        partials.forEachIndexed { index, partial ->
            val result = testPartialRouting(partial.text, partial.elapsedMs, partial.confidence)
            results.add(result)

            if (result.wouldTrigger && triggeredAt == null) {
                triggeredAt = index
                Log.d(TAG, "   ✓ Would trigger at partial #$index: \"${partial.text}\"")
            }
        }

        return SequenceTestResult(
            partials = partials,
            results = results,
            triggeredAtIndex = triggeredAt,
            triggeredByText = triggeredAt?.let { partials[it].text },
            totalElapsedMs = partials.lastOrNull()?.elapsedMs ?: 0
        )
    }

    /**
     * Simulate UtteranceEnd event (final transcript after silence detected).
     */
    fun testUtteranceEnd(
        text: String,
        totalElapsedMs: Long
    ): RoutingTestResult {
        Log.d(TAG, "🧪 TESTING UTTERANCE END: \"$text\" (total time: ${totalElapsedMs}ms)")

        // UtteranceEnd means user stopped speaking - route unconditionally
        val wordCount = text.split("\\s+".toRegex()).size
        val tier = classifyTier(wordCount, totalElapsedMs)

        val match = try {
            workflowRouter.route(text, -1, isPartial = false)
        } catch (e: Exception) {
            null
        }

        val wouldTrigger = match != null && match.definition.name != "conversational_default"

        return RoutingTestResult(
            text = text,
            elapsedMs = totalElapsedMs,
            wordCount = wordCount,
            decision = "FINAL_TRANSCRIPT",
            reason = "UtteranceEnd - process final transcript",
            wouldTrigger = wouldTrigger,
            matchedWorkflow = match?.definition?.name,
            tier = tier,
            decisions = listOf("UTTERANCE_END", "FINAL_ROUTING"),
            confidence = 1.0f
        )
    }

    /**
     * Generate realistic test scenarios based on common usage patterns.
     */
    fun generateScenarios(): List<TestScenario> = listOf(
        TestScenario(
            name = "Quick Command: pause",
            description = "User says 'pause' while music playing",
            partials = listOf(
                PartialTranscript("pause", 1200, 0.92f)
            ),
            expectedBehavior = "Should trigger immediately (Tier 1)"
        ),
        TestScenario(
            name = "Parameterized: start task grocery shopping",
            description = "User says task command with parameter",
            partials = listOf(
                PartialTranscript("start", 1500, 0.80f),
                PartialTranscript("start task", 2300, 0.88f),
                PartialTranscript("start task grocery", 3200, 0.92f),
                PartialTranscript("start task grocery shopping", 4100, 0.94f)
            ),
            expectedBehavior = "Should wait for complete phrase, not trigger at 'start task'"
        ),
        TestScenario(
            name = "Incomplete: place on",
            description = "User says 'place on' but wants to say 'place on the table'",
            partials = listOf(
                PartialTranscript("place", 1400, 0.85f),
                PartialTranscript("place on", 1900, 0.82f)
            ),
            expectedBehavior = "Should detect incompleteness (trailing preposition), wait"
        ),
        TestScenario(
            name = "Thinking out loud",
            description = "User mentions workflow trigger in middle of thinking",
            partials = listOf(
                PartialTranscript("so I'm thinking about", 2500, 0.88f),
                PartialTranscript("so I'm thinking about the project and we should start task", 5200, 0.85f),
                PartialTranscript("so I'm thinking about the project and we should start task planning the MVP", 7800, 0.87f)
            ),
            expectedBehavior = "Should route to conversational (13+ words) or wait for UtteranceEnd"
        ),
        TestScenario(
            name = "Cancellation: wait no cancel",
            description = "User changes their mind mid-command",
            partials = listOf(
                PartialTranscript("start task", 2200, 0.90f),
                PartialTranscript("start task wait no", 3100, 0.88f)
            ),
            expectedBehavior = "Should detect cancellation phrase and stop"
        ),
        TestScenario(
            name = "Too early: single word",
            description = "First partial arrives too quickly",
            partials = listOf(
                PartialTranscript("so", 1000, 0.75f)
            ),
            expectedBehavior = "Should ignore (< 1500ms elapsed)"
        )
    )

    private fun classifyTier(wordCount: Int, elapsedMs: Long): CommandTier = when {
        wordCount <= RoutingConfig.INSTANT_COMMAND_MAX_WORDS && elapsedMs < 2000 -> CommandTier.INSTANT
        wordCount <= RoutingConfig.QUICK_COMMAND_MAX_WORDS && elapsedMs < 3500 -> CommandTier.QUICK
        wordCount <= RoutingConfig.COMPLEX_COMMAND_MAX_WORDS && elapsedMs < 6000 -> CommandTier.COMPLEX
        else -> CommandTier.CONVERSATIONAL
    }
}

data class PartialTranscript(
    val text: String,
    val elapsedMs: Long,
    val confidence: Float = 0.85f
)

data class RoutingTestResult(
    val text: String,
    val elapsedMs: Long,
    val wordCount: Int,
    val decision: String,
    val reason: String,
    val wouldTrigger: Boolean,
    val matchedWorkflow: String?,
    val tier: CommandTier,
    val decisions: List<String>,
    val confidence: Float
) {
    fun toJson() = JSONObject().apply {
        put("text", text)
        put("elapsed_ms", elapsedMs)
        put("word_count", wordCount)
        put("decision", decision)
        put("reason", reason)
        put("would_trigger", wouldTrigger)
        put("matched_workflow", matchedWorkflow)
        put("tier", tier.name)
        put("confidence", confidence)
        put("decision_path", JSONArray(decisions))
    }
}

data class SequenceTestResult(
    val partials: List<PartialTranscript>,
    val results: List<RoutingTestResult>,
    val triggeredAtIndex: Int?,
    val triggeredByText: String?,
    val totalElapsedMs: Long
) {
    fun toJson() = JSONObject().apply {
        put("total_partials", partials.size)
        put("triggered_at_index", triggeredAtIndex)
        put("triggered_by_text", triggeredByText)
        put("total_elapsed_ms", totalElapsedMs)
        put("steps", JSONArray().apply {
            results.forEach { put(it.toJson()) }
        })
    }
}

data class TestScenario(
    val name: String,
    val description: String,
    val partials: List<PartialTranscript>,
    val expectedBehavior: String
) {
    fun toJson() = JSONObject().apply {
        put("name", name)
        put("description", description)
        put("expected_behavior", expectedBehavior)
        put("partials", JSONArray().apply {
            partials.forEach { partial ->
                put(JSONObject().apply {
                    put("text", partial.text)
                    put("elapsed_ms", partial.elapsedMs)
                    put("confidence", partial.confidence)
                })
            }
        })
    }
}
