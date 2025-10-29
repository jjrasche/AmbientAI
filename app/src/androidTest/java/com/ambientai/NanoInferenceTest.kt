package com.ambientai

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.aicore.GenerativeModel
import com.google.ai.edge.aicore.generationConfig
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration tests for Gemini Nano inference via AI Edge SDK.
 * Tests latency and quality of on-device LLM responses.
 */
@RunWith(AndroidJUnit4::class)
class NanoInferenceTest {

    private lateinit var context: Context
    private lateinit var model: GenerativeModel

    companion object {
        private const val TAG = "NanoInferenceTest"
    }

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext

        // Initialize Gemini Nano via AI Edge SDK
        model = GenerativeModel(
            generationConfig = generationConfig {
                context = this@NanoInferenceTest.context
                temperature = 0.7f
                topK = 40
                maxOutputTokens = 256
            }
        )

        Log.d(TAG, "Gemini Nano initialized")
    }

    @Test
    fun test1_helloWorld() = runBlocking {
        Log.d(TAG, "\n=== TEST 1: Hello World ===")

        val prompt = "Say hello"
        val startTime = System.currentTimeMillis()

        val response = model.generateContent(prompt)
        val latency = System.currentTimeMillis() - startTime

        Log.d(TAG, "Prompt: $prompt")
        Log.d(TAG, "Response: ${response.text ?: "null"}")
        Log.d(TAG, "Latency: ${latency}ms")

        // Assert response exists
        assert(!response.text.isNullOrEmpty()) { "Response should not be empty" }

        // Warn if latency is concerning
        if (latency > 1000) {
            Log.w(TAG, "WARNING: Latency >1s may not be viable for conversational use")
        } else if (latency < 500) {
            Log.i(TAG, "SUCCESS: Latency <500ms is excellent for conversational use")
        }
    }

    @Test
    fun test2_contextWindow() = runBlocking {
        Log.d(TAG, "\n=== TEST 2: Context Window ===")

        val transcript = """
I'm working on the Android migration. 
Need to update the build files.
Also have to fix that notification bug.
        """.trimIndent()

        val prompt = "$transcript\n\nBased on what I just said, what am I working on? Answer in one sentence."

        val startTime = System.currentTimeMillis()

        val response = model.generateContent(prompt)
        val latency = System.currentTimeMillis() - startTime

        Log.d(TAG, "Context: $transcript")
        Log.d(TAG, "Prompt: Based on what I just said, what am I working on?")
        Log.d(TAG, "Response: ${response.text ?: "null"}")
        Log.d(TAG, "Latency with context: ${latency}ms")

        // Assert response mentions the key topics
        val responseText = response.text?.lowercase() ?: ""
        val mentionsAndroid = responseText.contains("android") || responseText.contains("migration")

        if (mentionsAndroid) {
            Log.i(TAG, "SUCCESS: Response correctly identifies Android migration context")
        } else {
            Log.w(TAG, "WARNING: Response may not be understanding context correctly")
        }

        assert(!response.text.isNullOrEmpty()) { "Response should not be empty" }
    }

    @Test
    fun test3_shouldRespondClassifier() = runBlocking {
        Log.d(TAG, "\n=== TEST 3: Should-I-Respond Classifier ===")

        val testCases = listOf(
            "Took 1000mg Tylenol" to false,
            "What should I do about this headache?" to true,
            "Working on Android migration" to false,
            "How long have I been working on this?" to true,
            "Started task bug fixes" to false,
            "What's the current time?" to true
        )

        var correctCount = 0
        var totalLatency = 0L

        testCases.forEach { (transcript, expectedResponse) ->
            val prompt = "User said: '$transcript'. Should I respond? Answer only 'yes' or 'no'."

            val startTime = System.currentTimeMillis()

            val response = model.generateContent(prompt)
            val latency = System.currentTimeMillis() - startTime
            totalLatency += latency

            val shouldRespond = response.text?.trim()?.lowercase()?.startsWith("yes") ?: false
            val correct = shouldRespond == expectedResponse
            if (correct) correctCount++

            Log.d(TAG, "Transcript: '$transcript'")
            Log.d(TAG, "Expected: $expectedResponse | Got: $shouldRespond | Correct: $correct")
            Log.d(TAG, "Latency: ${latency}ms")
            Log.d(TAG, "---")
        }

        val accuracy = (correctCount.toFloat() / testCases.size) * 100
        val avgLatency = totalLatency / testCases.size

        Log.i(TAG, "RESULTS:")
        Log.i(TAG, "Accuracy: $accuracy% ($correctCount/${testCases.size})")
        Log.i(TAG, "Average Latency: ${avgLatency}ms")

        if (accuracy >= 80) {
            Log.i(TAG, "SUCCESS: Classifier accuracy is viable")
        } else {
            Log.w(TAG, "WARNING: Classifier accuracy may need improvement")
        }

        if (avgLatency < 500) {
            Log.i(TAG, "SUCCESS: Average latency supports real-time classification")
        } else {
            Log.w(TAG, "WARNING: Average latency may be too slow for per-chunk classification")
        }
    }

    @Test
    fun test4_conversationalQuality() = runBlocking {
        Log.d(TAG, "\n=== TEST 4: Conversational Response Quality ===")

        val context = """
[2024-10-29 08:30] Started task Android migration
[2024-10-29 09:15] Took 1000mg Tylenol
[2024-10-29 10:00] Working on build files
        """.trimIndent()

        val prompt = """Given this context from the user's recent activity:
$context

The user just asked: "What am I working on?"

Provide a natural, conversational response in 1-2 sentences."""

        val startTime = System.currentTimeMillis()

        val response = model.generateContent(prompt)
        val latency = System.currentTimeMillis() - startTime

        Log.d(TAG, "Context: $context")
        Log.d(TAG, "User query: What am I working on?")
        Log.d(TAG, "Response: ${response.text}")
        Log.d(TAG, "Latency: ${latency}ms")

        // Check if response is conversational (not too long, mentions key info)
        val wordCount = response.text?.split("\\s+".toRegex())?.size ?: 0

        if (wordCount in 10..50) {
            Log.i(TAG, "SUCCESS: Response length is conversational ($wordCount words)")
        } else {
            Log.w(TAG, "WARNING: Response may be too ${if (wordCount < 10) "short" else "long"} ($wordCount words)")
        }

        assert(response.text != null) { "Response should not be empty" }
    }
}