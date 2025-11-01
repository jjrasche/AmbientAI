package com.ambientai.core.llm

import android.content.Context
import android.util.Log
import com.google.ai.edge.aicore.GenerativeModel
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * Tests with model recreation to work around BUSY errors.
 * Recreates model every N inferences to prevent resource exhaustion.
 */
class GeminiNanoTester(private val context: Context) {

    companion object {
        private const val TAG = "NanoPerfTests"
        private const val MAX_INFERENCES_PER_MODEL = 10 // Recreate after this many
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

    private var model: GenerativeModel? = null
    private var inferenceCount = 0
    private var modelRecreationCount = 0

    suspend fun runAllTests(onProgress: (String) -> Unit): List<TestResult> = withContext(Dispatchers.IO) {
        val results = mutableListOf<TestResult>()

        try {
            // Initial model creation
            createModel()

            // Test 0: Concurrency check
            results.add(test0_concurrencyCheck(onProgress))
            delay(1000)

            // Test 1: Warmup
            results.add(test1_warmup(onProgress))
            delay(1000)

            // Test 2: Token throughput
            results.add(test2_tokenThroughput(onProgress))
            delay(1000)

            // Test 3: Inter-request timing
            results.add(test3_interRequestTiming(onProgress))
            delay(1000)

            // Test 4: Sustained usage
            results.add(test4_sustainedUsage(onProgress))

            // Add summary of recreations
            results.add(
                TestResult(
                    testName = "Model Recreations",
                    success = true,
                    message = "Recreated model $modelRecreationCount times",
                    metrics = mapOf("recreation_count" to modelRecreationCount.toDouble())
                )
            )

        } catch (e: Exception) {
            Log.e(TAG, "Test suite failed", e)
            results.add(
                TestResult(
                    testName = "Test Suite",
                    success = false,
                    message = "Test suite failed: ${e.message}"
                )
            )
        } finally {
            model?.close()
            Log.d(TAG, "Cleaned up final model")
        }

        results
    }

    private suspend fun createModel() {
        val start = System.currentTimeMillis()

        model?.close()

        model = GenerativeModel(
            generationConfig = com.google.ai.edge.aicore.generationConfig {
                context = this@GeminiNanoTester.context
                maxOutputTokens = 50
                temperature = 0.7f
                topK = 40
            }
        )

        inferenceCount = 0
        val elapsed = System.currentTimeMillis() - start

        Log.d(TAG, "Model created in ${elapsed}ms (recreation #$modelRecreationCount)")
        modelRecreationCount++
    }

    private suspend fun maybeRecreateModel() {
        if (inferenceCount >= MAX_INFERENCES_PER_MODEL) {
            Log.d(TAG, "Hit $inferenceCount inferences, recreating model...")
            delay(500) // Brief pause before recreation
            createModel()
            delay(500) // Brief pause after recreation
        }
    }

    private suspend fun doInference(prompt: String): String {
        maybeRecreateModel()

        val response = model?.generateContent(prompt) ?: throw IllegalStateException("Model is null")
        inferenceCount++

        return response.text ?: ""
    }

    private suspend fun test0_concurrencyCheck(
        onProgress: (String) -> Unit
    ): TestResult {
        onProgress("Test 0: Concurrency Check")

        return try {
            val successCount = AtomicInteger(0)
            val errorCount = AtomicInteger(0)
            val completionOrder = mutableListOf<Int>()
            val startTimes = mutableMapOf<Int, Long>()
            val endTimes = mutableMapOf<Int, Long>()

            // Fire 5 requests simultaneously
            coroutineScope {
                repeat(5) { i ->
                    launch {
                        try {
                            startTimes[i] = System.currentTimeMillis()
                            doInference("Request $i: say hello")
                            endTimes[i] = System.currentTimeMillis()

                            synchronized(completionOrder) {
                                completionOrder.add(i)
                            }
                            successCount.incrementAndGet()

                            val latency = endTimes[i]!! - startTimes[i]!!
                            Log.d(TAG, "Request $i completed in ${latency}ms")
                        } catch (e: Exception) {
                            errorCount.incrementAndGet()
                            Log.e(TAG, "Request $i failed: ${e.message}")
                        }
                    }
                }
            }

            Log.d(TAG, "Completion order: $completionOrder")

            val message = if (successCount.get() == 5) {
                "✓ All 5 succeeded. Order: ${completionOrder}"
            } else {
                "✗ Only ${successCount.get()}/5 succeeded, ${errorCount.get()} failed"
            }

            TestResult(
                testName = "Concurrency Check",
                success = successCount.get() == 5,
                message = message,
                metrics = mapOf(
                    "success_count" to successCount.get().toDouble(),
                    "error_count" to errorCount.get().toDouble()
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Concurrency check failed", e)
            TestResult(
                testName = "Concurrency Check",
                success = false,
                message = "Failed: ${e.message}"
            )
        }
    }

    private suspend fun test1_warmup(
        onProgress: (String) -> Unit
    ): TestResult {
        onProgress("Test 1: First Inference (Warmup)")

        return try {
            // Recreate model for clean warmup test
            createModel()
            delay(500)

            val warmupStart = System.currentTimeMillis()
            doInference("Hello, this is a warmup test")
            val warmupTime = System.currentTimeMillis() - warmupStart

            Log.d(TAG, "First inference: ${warmupTime}ms")

            delay(200)
            val secondStart = System.currentTimeMillis()
            doInference("This is the second inference")
            val secondTime = System.currentTimeMillis() - secondStart

            Log.d(TAG, "Second inference: ${secondTime}ms")

            val message = "First: ${warmupTime}ms, Second: ${secondTime}ms"

            TestResult(
                testName = "Warmup",
                success = true,
                message = message,
                metrics = mapOf(
                    "first_inference_ms" to warmupTime.toDouble(),
                    "second_inference_ms" to secondTime.toDouble(),
                    "warmup_overhead_ms" to (warmupTime - secondTime).toDouble()
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Warmup test failed", e)
            TestResult(
                testName = "Warmup",
                success = false,
                message = "Failed: ${e.message}"
            )
        }
    }

    private suspend fun test2_tokenThroughput(
        onProgress: (String) -> Unit
    ): TestResult {
        onProgress("Test 2: Token Throughput")

        val tokenLimits = listOf(5, 10, 15, 20, 30, 50)
        val iterations = 10

        return try {
            val results = mutableMapOf<Int, LatencyStats>()

            for (tokenLimit in tokenLimits) {
                onProgress("Testing ${tokenLimit} tokens...")

                val latencies = mutableListOf<Long>()

                repeat(iterations) {
                    delay(200)

                    val start = System.currentTimeMillis()
                    doInference("Say hello in approximately $tokenLimit words")
                    latencies.add(System.currentTimeMillis() - start)
                }

                results[tokenLimit] = calculateStats(latencies)
                Log.d(TAG, "${tokenLimit} tokens: ${results[tokenLimit]?.mean}ms avg")
            }

            val (firstTokenLatency, tokensPerSec) = calculateThroughput(results)

            val message = "First token: ${firstTokenLatency.toInt()}ms, ${tokensPerSec.toInt()} tok/sec"

            Log.d(TAG, "Calculated: $message")

            TestResult(
                testName = "Token Throughput",
                success = firstTokenLatency < 1000 && tokensPerSec > 5,
                message = message,
                metrics = buildMap {
                    put("first_token_latency_ms", firstTokenLatency)
                    put("tokens_per_sec", tokensPerSec)
                    results.forEach { (tokens, stats) ->
                        put("tokens_${tokens}_mean_ms", stats.mean)
                        put("tokens_${tokens}_p95_ms", stats.p95)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Token throughput failed", e)
            TestResult(
                testName = "Token Throughput",
                success = false,
                message = "Failed: ${e.message}"
            )
        }
    }

    private suspend fun test3_interRequestTiming(
        onProgress: (String) -> Unit
    ): TestResult {
        onProgress("Test 3: Inter-Request Timing")

        val delays = listOf(0L, 50L, 100L, 150L, 200L, 300L)
        val runsPerDelay = 10

        return try {
            val results = mutableMapOf<Long, Pair<Int, Int>>()

            for (testDelay in delays) {
                onProgress("Testing ${testDelay}ms delay...")

                var successCount = 0
                var errorCount = 0

                repeat(runsPerDelay) {
                    try {
                        if (testDelay > 0) delay(testDelay)
                        doInference("Request with ${testDelay}ms delay")
                        successCount++
                    } catch (e: Exception) {
                        errorCount++
                        Log.e(TAG, "Request failed with ${testDelay}ms delay: ${e.message}")
                    }
                }

                results[testDelay] = Pair(successCount, errorCount)
                Log.d(TAG, "${testDelay}ms delay: $successCount success, $errorCount errors")
            }

            val minSafeDelay = results.entries
                .sortedBy { it.key }
                .firstOrNull { it.value.second == 0 }
                ?.key ?: delays.last()

            val message = "Minimum safe delay: ${minSafeDelay}ms"

            TestResult(
                testName = "Inter-Request Timing",
                success = true,
                message = message,
                metrics = buildMap {
                    put("min_safe_delay_ms", minSafeDelay.toDouble())
                    results.forEach { (delay, counts) ->
                        put("delay_${delay}_success", counts.first.toDouble())
                        put("delay_${delay}_errors", counts.second.toDouble())
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Inter-request timing failed", e)
            TestResult(
                testName = "Inter-Request Timing",
                success = false,
                message = "Failed: ${e.message}"
            )
        }
    }

    private suspend fun test4_sustainedUsage(
        onProgress: (String) -> Unit
    ): TestResult {
        onProgress("Test 4: Sustained Usage")

        val runs = 50 // More runs to test recreation strategy

        return try {
            val latencies = mutableListOf<Long>()

            repeat(runs) { i ->
                delay(200)

                val start = System.currentTimeMillis()
                doInference("Request $i")
                latencies.add(System.currentTimeMillis() - start)

                if ((i + 1) % 10 == 0) {
                    Log.d(TAG, "Completed ${i + 1}/$runs inferences")
                }
            }

            val stats = calculateStats(latencies)

            // Compare first 10 vs last 10
            val firstTen = latencies.take(10).average()
            val lastTen = latencies.takeLast(10).average()
            val degradation = ((lastTen - firstTen) / firstTen) * 100

            Log.d(TAG, "Sustained: mean ${stats.mean}ms, degradation ${degradation.toInt()}%")

            val message = "Mean: ${stats.mean.toInt()}ms, ${if (degradation < 20) "stable" else "degrades ${degradation.toInt()}%"}"

            TestResult(
                testName = "Sustained Usage",
                success = degradation < 30,
                message = message,
                metrics = mapOf(
                    "mean_ms" to stats.mean,
                    "p95_ms" to stats.p95,
                    "degradation_pct" to degradation,
                    "first_ten_avg_ms" to firstTen,
                    "last_ten_avg_ms" to lastTen
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Sustained usage failed", e)
            TestResult(
                testName = "Sustained Usage",
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

    private fun calculateThroughput(results: Map<Int, LatencyStats>): Pair<Double, Double> {
        val points = results.map { (tokens, stats) -> Pair(tokens.toDouble(), stats.mean) }
        val n = points.size
        val sumX = points.sumOf { it.first }
        val sumY = points.sumOf { it.second }
        val sumXY = points.sumOf { it.first * it.second }
        val sumX2 = points.sumOf { it.first * it.first }

        val slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX)
        val intercept = (sumY - slope * sumX) / n

        val firstTokenLatency = intercept
        val tokensPerSec = 1000.0 / slope

        return Pair(firstTokenLatency, tokensPerSec)
    }
}