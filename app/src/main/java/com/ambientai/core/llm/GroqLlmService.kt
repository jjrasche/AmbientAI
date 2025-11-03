package com.ambientai.core.llm

import android.util.Log
import com.ambientai.BuildConfig
import com.ambientai.data.LlmRequest
import com.ambientai.data.LlmResponse
import com.ambientai.data.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Service for making inference calls to Groq API.
 * Validates inputs and returns JSONObject for workflow compatibility.
 */
class GroqLlmService {

    companion object {
        private const val TAG = "GroqLlmService"
        private const val API_URL = "https://api.groq.com/openai/v1/chat/completions"
        private const val MODEL = "llama-3.1-8b-instant"
        private const val TIMEOUT_MS = 10000
        private const val MAX_TOKENS_LIMIT = 8000
        private const val MIN_TEMPERATURE = 0.0f
        private const val MAX_TEMPERATURE = 2.0f
    }

    /**
     * Execute an LLM action.
     * Input/output is JSONObject for workflow compatibility.
     */
    fun execute(actionName: String, input: JSONObject): JSONObject {
        return when (actionName) {
            "llm.prompt" -> prompt(input)
            else -> errorResult("Unknown action: $actionName")
        }
    }

    private fun successResult(data: Map<String, Any?> = emptyMap()): JSONObject {
        return JSONObject().apply {
            put("success", true)
            data.forEach { (k, v) -> put(k, v) }
        }
    }

    private fun errorResult(message: String): JSONObject {
        return JSONObject().apply {
            put("success", false)
            put("error", message)
        }
    }

    /**
     * Generate LLM response.
     * Input: { "systemPrompt": "...", "userPrompt": "...", "temperature": 0.7, "maxTokens": 256 }
     */
    private fun prompt(input: JSONObject): JSONObject {
        // Validate required fields
        val systemPrompt = input.optString("systemPrompt", null)
            ?: return errorResult("Missing required field: systemPrompt")

        val userPrompt = input.optString("userPrompt", null)
            ?: return errorResult("Missing required field: userPrompt")

        if (systemPrompt.isBlank()) {
            return errorResult("systemPrompt cannot be empty")
        }

        if (userPrompt.isBlank()) {
            return errorResult("userPrompt cannot be empty")
        }

        // Validate optional fields with defaults
        val temperature = input.optDouble("temperature", 0.7).toFloat()
        if (temperature < MIN_TEMPERATURE || temperature > MAX_TEMPERATURE) {
            return errorResult("temperature must be between $MIN_TEMPERATURE and $MAX_TEMPERATURE")
        }

        val maxTokens = input.optInt("maxTokens", 256)
        if (maxTokens <= 0 || maxTokens > MAX_TOKENS_LIMIT) {
            return errorResult("maxTokens must be between 1 and $MAX_TOKENS_LIMIT")
        }

        // Make synchronous call - we're already in a coroutine context from WorkflowExecutor
        val result = runBlocking {
            generateResponse(
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                temperature = temperature,
                maxTokens = maxTokens
            )
        }

        return result.fold(
            onSuccess = { response ->
                successResult(mapOf("response" to response))
            },
            onFailure = { error ->
                errorResult(error.message ?: "LLM request failed")
            }
        )
    }

    /**
     * Generate a response from the LLM.
     * @param systemPrompt System instructions for the model
     * @param userPrompt User's query
     * @param temperature Randomness (0.0-2.0)
     * @param maxTokens Maximum tokens to generate
     * @return Generated text response
     */
    private suspend fun generateResponse(
        systemPrompt: String,
        userPrompt: String,
        temperature: Float = 0.7f,
        maxTokens: Int = 256
    ): Result<String> = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()

        try {
            val request = LlmRequest(
                model = MODEL,
                messages = listOf(
                    Message(role = "system", content = systemPrompt),
                    Message(role = "user", content = userPrompt)
                ),
                temperature = temperature,
                max_tokens = maxTokens,
                stream = false
            )

            val response = makeApiCall(request)
            val latency = System.currentTimeMillis() - startTime

            Log.d(TAG, "Response received in ${latency}ms")
            Log.d(TAG, "Tokens used: ${response.usage?.total_tokens ?: "unknown"}")

            val text = response.choices.firstOrNull()?.message?.content
                ?: return@withContext Result.failure(Exception("Empty response from API"))

            if (text.isBlank()) {
                return@withContext Result.failure(Exception("LLM returned blank response"))
            }

            Result.success(text)
        } catch (e: Exception) {
            Log.e(TAG, "API call failed", e)
            Result.failure(e)
        }
    }

    private fun makeApiCall(request: LlmRequest): LlmResponse {
        val url = URL(API_URL)
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${BuildConfig.GROQ_API_KEY}")
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
            }

            // Write request
            val requestJson = buildRequestJson(request)
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(requestJson)
                writer.flush()
            }

            // Read response
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorStream = connection.errorStream
                val errorBody = errorStream?.bufferedReader()?.use { it.readText() } ?: "No error body"
                throw Exception("API error $responseCode: $errorBody")
            }

            val responseBody = BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                reader.readText()
            }

            return parseResponse(responseBody)
        } finally {
            connection.disconnect()
        }
    }

    private fun buildRequestJson(request: LlmRequest): String {
        val json = JSONObject()
        json.put("model", request.model)
        json.put("temperature", request.temperature)
        json.put("max_tokens", request.max_tokens)
        json.put("stream", request.stream)

        val messages = JSONArray()
        request.messages.forEach { message ->
            val msgObj = JSONObject()
            msgObj.put("role", message.role)
            msgObj.put("content", message.content)
            messages.put(msgObj)
        }
        json.put("messages", messages)

        return json.toString()
    }

    private fun parseResponse(responseBody: String): LlmResponse {
        val json = JSONObject(responseBody)

        val choicesArray = json.getJSONArray("choices")
        val choices = mutableListOf<com.ambientai.data.Choice>()

        for (i in 0 until choicesArray.length()) {
            val choiceObj = choicesArray.getJSONObject(i)
            val messageObj = choiceObj.getJSONObject("message")

            val message = Message(
                role = messageObj.getString("role"),
                content = messageObj.getString("content")
            )

            choices.add(
                com.ambientai.data.Choice(
                    message = message,
                    finish_reason = choiceObj.optString("finish_reason", null)
                )
            )
        }

        val usage = if (json.has("usage")) {
            val usageObj = json.getJSONObject("usage")
            com.ambientai.data.Usage(
                prompt_tokens = usageObj.getInt("prompt_tokens"),
                completion_tokens = usageObj.getInt("completion_tokens"),
                total_tokens = usageObj.getInt("total_tokens")
            )
        } else {
            null
        }

        return LlmResponse(
            id = json.getString("id"),
            choices = choices,
            usage = usage
        )
    }
}