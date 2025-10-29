package com.ambientai.core.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.aicore.GenerativeModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Tests Gemini Nano inference capabilities.
 * Uses correct AI Edge SDK API.
 */
class GeminiNanoTester(private val context: Context) {

    private var model: GenerativeModel? = null

    companion object {
        private const val TAG = "NanoTester"
    }

    data class TestResult(
        val testName: String,
        val success: Boolean,
        val message: String,
        val latency: Long? = null
    )

    suspend fun runAllTests(onProgress: (String) -> Unit): List<TestResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<TestResult>()

        results.add(test0_initializeModel(onProgress))

        if (model != null) {
            results.add(test1_helloWorld(onProgress))
            results.add(test2_contextWindow(onProgress))
            results.add(test3_shouldRespondClassifier(onProgress))
            results.add(test4_conversationalQuality(onProgress))
        }

        results
    }

    private fun test0_initializeModel(onProgress: (String) -> Unit): TestResult {
        onProgress("Test 0: Initialize Model")

        return try {
            model = GenerativeModel(
                generationConfig = com.google.ai.edge.aicore.generationConfig {
                    context = this@GeminiNanoTester.context
                    maxOutputTokens = 256
                    temperature = 0.7f
                    topK = 40
                }
            )

            Log.d(TAG, "Model initialized successfully")

            TestResult(
                testName = "Initialize Model",
                success = true,
                message = "Gemini Nano ready"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Init failed", e)
            TestResult(
                testName = "Initialize Model",
                success = false,
                message = "Failed: ${e.message}"
            )
        }
    }

    private suspend fun test1_helloWorld(onProgress: (String) -> Unit): TestResult {
        onProgress("Test 1: Hello World")

        val prompt = "Say hello"
        val startTime = System.currentTimeMillis()

        return try {
            val response = model?.generateContent(prompt)
            val latency = System.currentTimeMillis() - startTime
            val text = response?.text

            Log.d(TAG, "Response: $text")
            Log.d(TAG, "Latency: ${latency}ms")

            TestResult(
                testName = "Hello World",
                success = !text.isNullOrEmpty(),
                message = "$text (${latency}ms)",
                latency = latency
            )
        } catch (e: Exception) {
            Log.e(TAG, "Hello world failed", e)
            TestResult(
                testName = "Hello World",
                success = false,
                message = "Failed: ${e.message}"
            )
        }
    }

    private suspend fun test2_contextWindow(onProgress: (String) -> Unit): TestResult {
        onProgress("Test 2: Context Window")

        val transcript = """
I'm working on the Android migration.
Need to update the build files.
Also have to fix that notification bug.
        """.trimIndent()

        val prompt = "$transcript\n\nBased on what I just said, what am I working on? Answer in one sentence."
        val startTime = System.currentTimeMillis()

        return try {
            val response = model?.generateContent(prompt)
            val latency = System.currentTimeMillis() - startTime
            val text = response?.text

            val responseText = text?.lowercase() ?: ""
            val mentionsAndroid = responseText.contains("android") || responseText.contains("migration")

            Log.d(TAG, "Context response: $text")
            Log.d(TAG, "Mentions Android: $mentionsAndroid")

            TestResult(
                testName = "Context Window",
                success = mentionsAndroid,
                message = if (mentionsAndroid) "✓ Understood context (${latency}ms)" else "✗ Missed context",
                latency = latency
            )
        } catch (e: Exception) {
            Log.e(TAG, "Context test failed", e)
            TestResult(
                testName = "Context Window",
                success = false,
                message = "Failed: ${e.message}"
            )
        }
    }

    private suspend fun test3_shouldRespondClassifier(onProgress: (String) -> Unit): TestResult {
        onProgress("Test 3: Classifier")

        val testCases = listOf(
            "Took 1000mg Tylenol" to false,
            "What should I do about this headache?" to true,
            "Working on Android migration" to false,
            "How long have I been working on this?" to true
        )

        var correctCount = 0
        var totalLatency = 0L

        testCases.forEach { (transcript, expectedResponse) ->
            val prompt = "User said: '$transcript'. Should I respond? Answer only 'yes' or 'no'."
            val startTime = System.currentTimeMillis()

            try {
                val response = model?.generateContent(prompt)
                val latency = System.currentTimeMillis() - startTime
                totalLatency += latency

                val text = response?.text
                val shouldRespond = text?.trim()?.lowercase()?.startsWith("yes") ?: false
                if (shouldRespond == expectedResponse) correctCount++

            } catch (e: Exception) {
                Log.e(TAG, "Classifier case failed: $transcript", e)
            }
        }

        val accuracy = (correctCount.toFloat() / testCases.size) * 100
        val avgLatency = totalLatency / testCases.size

        Log.d(TAG, "Accuracy: $accuracy%")
        Log.d(TAG, "Avg latency: ${avgLatency}ms")

        return TestResult(
            testName = "Classifier",
            success = accuracy >= 75,
            message = "${accuracy.toInt()}% accurate, ${avgLatency}ms avg",
            latency = avgLatency
        )
    }

    private suspend fun test4_conversationalQuality(onProgress: (String) -> Unit): TestResult {
        onProgress("Test 4: Conversational Quality")

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

        return try {
            val response = model?.generateContent(prompt)
            val latency = System.currentTimeMillis() - startTime
            val text = response?.text

            val wordCount = text?.split("\\s+".toRegex())?.size ?: 0
            val goodLength = wordCount in 10..50

            Log.d(TAG, "Response: $text")
            Log.d(TAG, "Word count: $wordCount")

            TestResult(
                testName = "Conversational Quality",
                success = goodLength,
                message = "${wordCount} words, ${latency}ms",
                latency = latency
            )
        } catch (e: Exception) {
            Log.e(TAG, "Quality test failed", e)
            TestResult(
                testName = "Conversational Quality",
                success = false,
                message = "Failed: ${e.message}"
            )
        }
    }
}