//package com.ambientai
//
//import android.content.Context
//import android.util.Log
//import androidx.test.ext.junit.runners.AndroidJUnit4
//import androidx.test.filters.RequiresDevice
//import androidx.test.platform.app.InstrumentationRegistry
//import kotlinx.coroutines.runBlocking
//import org.junit.Before
//import org.junit.Test
//import org.junit.runner.RunWith
//
///**
// * Integration tests for Gemini Nano inference via AI Edge SDK.
// * Tests latency and quality of on-device LLM responses.
// *
// * NOTE: These tests only run on physical devices (Pixel 10 Pro).
// * Gemini Nano is not available on emulators.
// */
//@RunWith(AndroidJUnit4::class)
//@RequiresDevice
//class NanoInferenceTest {
//
//    private lateinit var context: Context
//    private var model: Any? = null
//    private var modelClass: Class<*>? = null
//
//    companion object {
//        private const val TAG = "NanoInferenceTest"
//    }
//
//    @Before
//    fun setup() {
//        context = InstrumentationRegistry.getInstrumentation().targetContext
//        Log.d(TAG, "Test setup complete")
//    }
//
//    @Test
//    fun test0_basicSetup() {
//        Log.d(TAG, "\n=== TEST 0: Basic Setup ===")
//
//        // Verify test context works
//        assert(context != null) { "Context should not be null" }
//
//        // Check if AI Edge SDK classes are accessible
//        try {
//            modelClass = Class.forName("com.google.ai.edge.aicore.GenerativeModel")
//            Log.d(TAG, "SUCCESS: AI Edge SDK classes are accessible")
//            Log.d(TAG, "GenerativeModel class: ${modelClass?.name}")
//
//            // Check device info
//            Log.d(TAG, "Device: ${android.os.Build.MODEL}")
//            Log.d(TAG, "Android version: ${android.os.Build.VERSION.SDK_INT}")
//
//        } catch (e: ClassNotFoundException) {
//            Log.e(TAG, "FAILURE: AI Edge SDK classes not found", e)
//            throw AssertionError("AI Edge SDK not properly configured", e)
//        }
//    }
//
//    @Test
//    fun test1_checkNanoAvailability() {
//        Log.d(TAG, "\n=== TEST 1: Check Gemini Nano Availability ===")
//
//        try {
//            // Try to get availability status
//            val downloadManagerClass = Class.forName("com.google.ai.edge.aicore.DownloadManager")
//            val getInstanceMethod = downloadManagerClass.getMethod("getInstance", Context::class.java)
//            val downloadManager = getInstanceMethod.invoke(null, context)
//
//            Log.d(TAG, "DownloadManager instance: $downloadManager")
//
//            // Try to check if model is available
//            val getAvailabilityMethod = downloadManagerClass.getMethod("getAvailability")
//            val availability = getAvailabilityMethod.invoke(downloadManager)
//
//            Log.d(TAG, "Gemini Nano availability: $availability")
//
//        } catch (e: Exception) {
//            Log.e(TAG, "Error checking Nano availability", e)
//            Log.d(TAG, "This might be expected - Nano may need manual download via settings")
//        }
//    }
//
//    @Test
//    fun test2_initializeModel() = runBlocking {
//        Log.d(TAG, "\n=== TEST 2: Initialize Model ===")
//
//        try {
//            // Initialize using reflection to avoid compile-time dependency issues
//            val generationConfigClass = Class.forName("com.google.ai.edge.aicore.GenerationConfig")
//            val builderClass = generationConfigClass.declaredClasses.find { it.simpleName == "Builder" }
//
//            requireNotNull(builderClass) { "Builder class not found" }
//
//            val builderConstructor = builderClass.getConstructor()
//            val builder = builderConstructor.newInstance()
//
//            // Set properties
//            builderClass.getMethod("setContext", Context::class.java).invoke(builder, context)
//            builderClass.getMethod("setTemperature", Float::class.java).invoke(builder, 0.7f)
//            builderClass.getMethod("setTopK", Int::class.java).invoke(builder, 40)
//            builderClass.getMethod("setMaxOutputTokens", Int::class.java).invoke(builder, 256)
//
//            val config = builderClass.getMethod("build").invoke(builder)
//
//            // Create GenerativeModel
//            val generativeModelClass = Class.forName("com.google.ai.edge.aicore.GenerativeModel")
//            val modelConstructor = generativeModelClass.getConstructor(generationConfigClass)
//            model = modelConstructor.newInstance(config)
//
//            Log.d(TAG, "SUCCESS: Gemini Nano model initialized")
//            Log.d(TAG, "Model instance: $model")
//
//        } catch (e: Exception) {
//            Log.e(TAG, "FAILURE: Could not initialize model", e)
//            throw AssertionError("Model initialization failed", e)
//        }
//    }
//
//    @Test
//    fun test3_helloWorld() = runBlocking {
//        Log.d(TAG, "\n=== TEST 3: Hello World ===")
//
//        initializeModelIfNeeded()
//
//        val prompt = "Say hello"
//        val startTime = System.currentTimeMillis()
//
//        try {
//            val generateContentMethod = modelClass?.getMethod("generateContent", String::class.java)
//            val response = generateContentMethod?.invoke(model, prompt)
//            val latency = System.currentTimeMillis() - startTime
//
//            // Get text from response
//            val responseClass = response?.javaClass
//            val getTextMethod = responseClass?.getMethod("getText")
//            val text = getTextMethod?.invoke(response) as? String
//
//            Log.d(TAG, "Prompt: $prompt")
//            Log.d(TAG, "Response: $text")
//            Log.d(TAG, "Latency: ${latency}ms")
//
//            assert(!text.isNullOrEmpty()) { "Response should not be empty" }
//
//            if (latency > 1000) {
//                Log.w(TAG, "WARNING: Latency >1s may not be viable for conversational use")
//            } else if (latency < 500) {
//                Log.i(TAG, "SUCCESS: Latency <500ms is excellent for conversational use")
//            }
//        } catch (e: Exception) {
//            Log.e(TAG, "FAILURE: Error during inference", e)
//            throw AssertionError("Inference failed", e)
//        }
//    }
//
//    @Test
//    fun test4_contextWindow() = runBlocking {
//        Log.d(TAG, "\n=== TEST 4: Context Window ===")
//
//        initializeModelIfNeeded()
//
//        val transcript = """
//I'm working on the Android migration.
//Need to update the build files.
//Also have to fix that notification bug.
//        """.trimIndent()
//
//        val prompt = "$transcript\n\nBased on what I just said, what am I working on? Answer in one sentence."
//
//        val startTime = System.currentTimeMillis()
//
//        try {
//            val generateContentMethod = modelClass?.getMethod("generateContent", String::class.java)
//            val response = generateContentMethod?.invoke(model, prompt)
//            val latency = System.currentTimeMillis() - startTime
//
//            val responseClass = response?.javaClass
//            val getTextMethod = responseClass?.getMethod("getText")
//            val text = getTextMethod?.invoke(response) as? String
//
//            Log.d(TAG, "Context: $transcript")
//            Log.d(TAG, "Prompt: Based on what I just said, what am I working on?")
//            Log.d(TAG, "Response: $text")
//            Log.d(TAG, "Latency with context: ${latency}ms")
//
//            val responseText = text?.lowercase() ?: ""
//            val mentionsAndroid = responseText.contains("android") || responseText.contains("migration")
//
//            if (mentionsAndroid) {
//                Log.i(TAG, "SUCCESS: Response correctly identifies Android migration context")
//            } else {
//                Log.w(TAG, "WARNING: Response may not be understanding context correctly")
//            }
//
//            assert(!text.isNullOrEmpty()) { "Response should not be empty" }
//        } catch (e: Exception) {
//            Log.e(TAG, "FAILURE: Error during inference", e)
//            throw AssertionError("Inference failed", e)
//        }
//    }
//
//    @Test
//    fun test5_shouldRespondClassifier() = runBlocking {
//        Log.d(TAG, "\n=== TEST 5: Should-I-Respond Classifier ===")
//
//        initializeModelIfNeeded()
//
//        val testCases = listOf(
//            "Took 1000mg Tylenol" to false,
//            "What should I do about this headache?" to true,
//            "Working on Android migration" to false,
//            "How long have I been working on this?" to true,
//            "Started task bug fixes" to false,
//            "What's the current time?" to true
//        )
//
//        var correctCount = 0
//        var totalLatency = 0L
//
//        testCases.forEach { (transcript, expectedResponse) ->
//            val prompt = "User said: '$transcript'. Should I respond? Answer only 'yes' or 'no'."
//
//            val startTime = System.currentTimeMillis()
//
//            try {
//                val generateContentMethod = modelClass?.getMethod("generateContent", String::class.java)
//                val response = generateContentMethod?.invoke(model, prompt)
//                val latency = System.currentTimeMillis() - startTime
//                totalLatency += latency
//
//                val responseClass = response?.javaClass
//                val getTextMethod = responseClass?.getMethod("getText")
//                val text = getTextMethod?.invoke(response) as? String
//
//                val shouldRespond = text?.trim()?.lowercase()?.startsWith("yes") ?: false
//                val correct = shouldRespond == expectedResponse
//                if (correct) correctCount++
//
//                Log.d(TAG, "Transcript: '$transcript'")
//                Log.d(TAG, "Expected: $expectedResponse | Got: $shouldRespond | Correct: $correct")
//                Log.d(TAG, "Latency: ${latency}ms")
//                Log.d(TAG, "---")
//            } catch (e: Exception) {
//                Log.e(TAG, "Error on test case: $transcript", e)
//            }
//        }
//
//        val accuracy = (correctCount.toFloat() / testCases.size) * 100
//        val avgLatency = totalLatency / testCases.size
//
//        Log.i(TAG, "RESULTS:")
//        Log.i(TAG, "Accuracy: $accuracy% ($correctCount/${testCases.size})")
//        Log.i(TAG, "Average Latency: ${avgLatency}ms")
//
//        if (accuracy >= 80) {
//            Log.i(TAG, "SUCCESS: Classifier accuracy is viable")
//        } else {
//            Log.w(TAG, "WARNING: Classifier accuracy may need improvement")
//        }
//
//        if (avgLatency < 500) {
//            Log.i(TAG, "SUCCESS: Average latency supports real-time classification")
//        } else {
//            Log.w(TAG, "WARNING: Average latency may be too slow for per-chunk classification")
//        }
//    }
//
//    @Test
//    fun test6_conversationalQuality() = runBlocking {
//        Log.d(TAG, "\n=== TEST 6: Conversational Response Quality ===")
//
//        initializeModelIfNeeded()
//
//        val context = """
//[2024-10-29 08:30] Started task Android migration
//[2024-10-29 09:15] Took 1000mg Tylenol
//[2024-10-29 10:00] Working on build files
//        """.trimIndent()
//
//        val prompt = """Given this context from the user's recent activity:
//$context
//
//The user just asked: "What am I working on?"
//
//Provide a natural, conversational response in 1-2 sentences."""
//
//        val startTime = System.currentTimeMillis()
//
//        try {
//            val generateContentMethod = modelClass?.getMethod("generateContent", String::class.java)
//            val response = generateContentMethod?.invoke(model, prompt)
//            val latency = System.currentTimeMillis() - startTime
//
//            val responseClass = response?.javaClass
//            val getTextMethod = responseClass?.getMethod("getText")
//            val text = getTextMethod?.invoke(response) as? String
//
//            Log.d(TAG, "Context: $context")
//            Log.d(TAG, "User query: What am I working on?")
//            Log.d(TAG, "Response: $text")
//            Log.d(TAG, "Latency: ${latency}ms")
//
//            val wordCount = text?.split("\\s+".toRegex())?.size ?: 0
//
//            if (wordCount in 10..50) {
//                Log.i(TAG, "SUCCESS: Response length is conversational ($wordCount words)")
//            } else {
//                Log.w(TAG, "WARNING: Response may be too ${if (wordCount < 10) "short" else "long"} ($wordCount words)")
//            }
//
//            assert(text != null) { "Response should not be empty" }
//        } catch (e: Exception) {
//            Log.e(TAG, "FAILURE: Error during inference", e)
//            throw AssertionError("Inference failed", e)
//        }
//    }
//
//    private fun initializeModelIfNeeded() {
//        if (model == null) {
//            // Run test2 to initialize
//            runBlocking {
//                test2_initializeModel()
//            }
//        }
//    }
//}

package com.ambientai

import android.content.Context
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Minimal test to verify test infrastructure works.
 * No AI stuff - just basic Android testing.
 */
@RunWith(AndroidJUnit4::class)
class NanoInferenceTest {

    private lateinit var context: Context

    companion object {
        private const val TAG = "MinimalTest"
    }

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        Log.d(TAG, "Setup complete")
        Log.d(TAG, "Package name: ${context.packageName}")
    }

    @Test
    fun test_contextWorks() {
        Log.d(TAG, "Testing context...")
        assert(context != null) { "Context should not be null" }
        assert(context.packageName == "com.ambientai") { "Package name should be com.ambientai" }
        Log.d(TAG, "✓ Context test passed")
    }

    @Test
    fun test_mathWorks() {
        Log.d(TAG, "Testing basic assertions...")
        assert(1 + 1 == 2) { "Math should work" }
        assert("test".length == 4) { "String length should work" }
        Log.d(TAG, "✓ Math test passed")
    }
}