package com.ambientai.core.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.aicore.GenerativeModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sqrt

/**
 * Comprehensive performance tests for Gemini Nano.
 * Tests cold start, warm model latency, token limits, and real workflows.
 */
class GeminiNanoTester(private val context: Context) {

    companion object {
        private const val TAG = "NanoPerfTests"
    }

    data class TestResult(
        val testName: String,
        val success: Boolean,
        val message: String,
        val metrics: Map<String, Double>? = null
    )

    data class LatencyStats(
        val mean: Double,
        val median: Double,
        val p95: Double,
        val min: Double,
        val max: Double,
        val stdDev: Double
    )

    // Test data structures
    data class TokenLimitTest(
        val maxTokens: Int,
        val prompt: String
    )

    data class ExtractionTest(
        val name: String,
        val prompt: String,
        val expectedField: String
    )

    data class ClassifierTest(
        val transcript: String,
        val shouldRespond: Boolean
    )

    suspend fun runAllTests(onProgress: (String) -> Unit): List<TestResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<TestResult>()

        // Test 1: Cold start
        results.add(test1_coldStart(onProgress))

        // Test 2: Token limit sweep
        results.add(test2_tokenLimitSweep(onProgress))

        // Test 3: Extraction tasks
        results.add(test3_extractionTasks(onProgress))

        // Test 4: Classifier consistency
        results.add(test4_classifierConsistency(onProgress))

        // Test 5: Rapid fire
        results.add(test5_rapidFire(onProgress))

        results
    }

    private suspend fun test1_coldStart(onProgress: (String) -> Unit): TestResult {
        onProgress("Test 1: Cold Start (Model Loading)")

        val startTime = System.currentTimeMillis()

        return try {
            val model = GenerativeModel(
                generationConfig = com.google.ai.edge.aicore.generationConfig {
                    context = this@GeminiNanoTester.context
                    maxOutputTokens = 10
                    temperature = 0.7f
                    topK = 40
                }
            )

            // Warm-up inference
            model.generateContent("Hello")
            val coldStartTime = System.currentTimeMillis() - startTime

            Log.d(TAG, "Cold start time: ${coldStartTime}ms")

            TestResult(
                testName = "Cold Start",
                success = true,
                message = "${coldStartTime}ms (unavoidable baseline)",
                metrics = mapOf("cold_start_ms" to coldStartTime.toDouble())
            )
        } catch (e: Exception) {
            Log.e(TAG, "Cold start failed", e)
            TestResult(
                testName = "Cold Start",
                success = false,
                message = "Failed: ${e.message}"
            )
        }
    }

    private suspend fun test2_tokenLimitSweep(onProgress: (String) -> Unit): TestResult {
        onProgress("Test 2: Token Limit Sweep")

        val tokenTests = listOf(
            TokenLimitTest(5, "Say hello in 5 words"),
            TokenLimitTest(10, "Say hello in 10 words"),
            TokenLimitTest(15, "Say hello in 15 words"),
            TokenLimitTest(20, "Say hello in 20 words"),
            TokenLimitTest(30, "Say hello in 30 words"),
            TokenLimitTest(50, "Say hello")
        )

        val iterations = 10
        val results = mutableMapOf<Int, LatencyStats>()

        try {
            for (test in tokenTests) {
                onProgress("Testing ${test.maxTokens} tokens...")

                val model = GenerativeModel(
                    generationConfig = com.google.ai.edge.aicore.generationConfig {
                        context = this@GeminiNanoTester.context
                        maxOutputTokens = test.maxTokens
                        temperature = 0.7f
                        topK = 40
                    }
                )

                // Warm up
                model.generateContent(test.prompt)

                // Measure
                val latencies = mutableListOf<Long>()
                repeat(iterations) {
                    val start = System.currentTimeMillis()
                    model.generateContent(test.prompt)
                    latencies.add(System.currentTimeMillis() - start)
                }

                results[test.maxTokens] = calculateStats(latencies)

                Log.d(TAG, "Tokens: ${test.maxTokens}, Mean: ${results[test.maxTokens]?.mean}ms")
            }

            // Find optimal token limit (< 500ms p95)
            val viable = results.filter { it.value.p95 < 500 }
            val optimalTokens = viable.keys.maxOrNull()

            val message = if (optimalTokens != null) {
                "✓ Optimal: ${optimalTokens} tokens (${viable[optimalTokens]?.mean?.toInt()}ms avg)"
            } else {
                "✗ All configs > 500ms"
            }

            return TestResult(
                testName = "Token Limit Sweep",
                success = optimalTokens != null,
                message = message,
                metrics = results.flatMap { (tokens, stats) ->
                    listOf(
                        "tokens_${tokens}_mean" to stats.mean,
                        "tokens_${tokens}_p95" to stats.p95
                    )
                }.toMap()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Token sweep failed", e)
            return TestResult(
                testName = "Token Limit Sweep",
                success = false,
                message = "Failed: ${e.message}"
            )
        }
    }

    private suspend fun test3_extractionTasks(onProgress: (String) -> Unit): TestResult {
        onProgress("Test 3: Real Workflow Extraction")

        val extractionTests = listOf(
            ExtractionTest(
                "Medication",
                "Extract medication name from: 'Took 1000mg Tylenol this morning'. Return ONLY the medication name, nothing else.",
                "tylenol"
            ),
            ExtractionTest(
                "Task",
                "Extract task name from: 'Working on Android migration'. Return ONLY the task name, nothing else.",
                "android"
            ),
            ExtractionTest(
                "Food",
                "Extract food from: 'Just had scrambled eggs for breakfast'. Return ONLY the food name, nothing else.",
                "egg"
            )
        )

        val iterations = 10
        val results = mutableMapOf<String, Pair<LatencyStats, Int>>() // name -> (stats, correctCount)

        try {
            val model = GenerativeModel(
                generationConfig = com.google.ai.edge.aicore.generationConfig {
                    context = this@GeminiNanoTester.context
                    maxOutputTokens = 10
                    temperature = 0.3f // Lower temp for extraction
                    topK = 20
                }
            )

            // Warm up
            model.generateContent("Test")

            for (test in extractionTests) {
                onProgress("Testing ${test.name} extraction...")

                val latencies = mutableListOf<Long>()
                var correctCount = 0

                repeat(iterations) {
                    val start = System.currentTimeMillis()
                    val response = model.generateContent(test.prompt)
                    val latency = System.currentTimeMillis() - start
                    latencies.add(latency)

                    // Check if extraction is correct
                    val text = response?.text?.lowercase() ?: ""
                    if (text.contains(test.expectedField)) {
                        correctCount++
                    }
                }

                results[test.name] = Pair(calculateStats(latencies), correctCount)

                Log.d(TAG, "${test.name}: ${correctCount}/${iterations} correct, ${results[test.name]?.first?.mean}ms avg")
            }

            val avgLatency = results.values.map { it.first.mean }.average()
            val avgAccuracy = results.values.map { it.second }.average() / iterations * 100

            val message = "Avg: ${avgLatency.toInt()}ms, ${avgAccuracy.toInt()}% accurate"

            return TestResult(
                testName = "Extraction Tasks",
                success = avgLatency < 500 && avgAccuracy > 70,
                message = message,
                metrics = results.flatMap { (name, pair) ->
                    listOf(
                        "${name}_mean_ms" to pair.first.mean,
                        "${name}_accuracy" to (pair.second.toDouble() / iterations * 100)
                    )
                }.toMap()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Extraction test failed", e)
            return TestResult(
                testName = "Extraction Tasks",
                success = false,
                message = "Failed: ${e.message}"
            )
        }
    }

    private suspend fun test4_classifierConsistency(onProgress: (String) -> Unit): TestResult {
        onProgress("Test 4: Classifier Consistency")

        val classifierTests = listOf(
            ClassifierTest("Took 1000mg Tylenol", false),
            ClassifierTest("What should I do about this headache?", true),
            ClassifierTest("Working on Android migration", false),
            ClassifierTest("How long have I been working on this?", true),
            ClassifierTest("Started task bug fixes", false),
            ClassifierTest("What's the current time?", true),
            ClassifierTest("Just had breakfast", false),
            ClassifierTest("Should I take a break?", true),
            ClassifierTest("Feeling tired", false),
            ClassifierTest("What do you think I should do?", true)
        )

        val iterations = 5
        val latencies = mutableListOf<Long>()
        var correctCount = 0
        val totalTests = classifierTests.size * iterations

        try {
            val model = GenerativeModel(
                generationConfig = com.google.ai.edge.aicore.generationConfig {
                    context = this@GeminiNanoTester.context
                    maxOutputTokens = 5 // Just "yes" or "no"
                    temperature = 0.3f
                    topK = 10
                }
            )

            // Warm up
            model.generateContent("Yes or no: should I respond?")

            for (test in classifierTests) {
                repeat(iterations) {
                    val prompt = "User said: '${test.transcript}'. Should I respond? Answer ONLY 'yes' or 'no'."

                    val start = System.currentTimeMillis()
                    val response = model.generateContent(prompt)
                    val latency = System.currentTimeMillis() - start
                    latencies.add(latency)

                    val shouldRespond = response?.text?.trim()?.lowercase()?.startsWith("yes") ?: false
                    if (shouldRespond == test.shouldRespond) {
                        correctCount++
                    }
                }
            }

            val stats = calculateStats(latencies)
            val accuracy = (correctCount.toDouble() / totalTests) * 100

            Log.d(TAG, "Classifier accuracy: $accuracy%, p95: ${stats.p95}ms")

            return TestResult(
                testName = "Classifier",
                success = accuracy >= 75 && stats.p95 < 500,
                message = "${accuracy.toInt()}% accurate, ${stats.p95.toInt()}ms p95",
                metrics = mapOf(
                    "accuracy" to accuracy,
                    "mean_ms" to stats.mean,
                    "p95_ms" to stats.p95
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Classifier test failed", e)
            return TestResult(
                testName = "Classifier",
                success = false,
                message = "Failed: ${e.message}"
            )
        }
    }

    private suspend fun test5_rapidFire(onProgress: (String) -> Unit): TestResult {
        onProgress("Test 5: Rapid Fire (20 consecutive)")

        val runs = 20

        try {
            val model = GenerativeModel(
                generationConfig = com.google.ai.edge.aicore.generationConfig {
                    context = this@GeminiNanoTester.context
                    maxOutputTokens = 10
                    temperature = 0.7f
                    topK = 40
                }
            )

            // Warm up
            model.generateContent("Test")

            // Rapid fire
            val latencies = mutableListOf<Long>()
            repeat(runs) { i ->
                val start = System.currentTimeMillis()
                model.generateContent("Quick response $i")
                latencies.add(System.currentTimeMillis() - start)
            }

            val stats = calculateStats(latencies)

            // Check for degradation (last 5 vs first 5)
            val firstFive = latencies.take(5).average()
            val lastFive = latencies.takeLast(5).average()
            val degradation = ((lastFive - firstFive) / firstFive) * 100

            Log.d(TAG, "Rapid fire: mean ${stats.mean}ms, degradation: ${degradation.toInt()}%")

            val message = "Mean: ${stats.mean.toInt()}ms, ${if (degradation < 20) "stable" else "degrades ${degradation.toInt()}%"}"

            return TestResult(
                testName = "Rapid Fire",
                success = stats.mean < 500 && degradation < 30,
                message = message,
                metrics = mapOf(
                    "mean_ms" to stats.mean,
                    "p95_ms" to stats.p95,
                    "degradation_pct" to degradation
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Rapid fire test failed", e)
            return TestResult(
                testName = "Rapid Fire",
                success = false,
                message = "Failed: ${e.message}"
            )
        }
    }

    private fun calculateStats(latencies: List<Long>): LatencyStats {
        val sorted = latencies.sorted()
        val n = sorted.size
        val mean = sorted.average()
        val median = if (n % 2 == 0) {
            (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
        } else {
            sorted[n / 2].toDouble()
        }
        val p95Index = ((n - 1) * 0.95).toInt()
        val p95 = sorted[p95Index].toDouble()

        val variance = sorted.map { (it - mean) * (it - mean) }.average()
        val stdDev = sqrt(variance)

        return LatencyStats(
            mean = mean,
            median = median,
            p95 = p95,
            min = sorted.first().toDouble(),
            max = sorted.last().toDouble(),
            stdDev = stdDev
        )
    }
}