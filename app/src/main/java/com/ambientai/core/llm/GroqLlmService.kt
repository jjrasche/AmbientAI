package com.ambientai.core.llm

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

class GroqLlmService {
    companion object {
        private const val API_URL = "https://api.groq.com/openai/v1/chat/completions"
        private const val MODEL = "llama-3.1-8b-instant"
        private const val TIMEOUT_MS = 10000
        private const val MAX_TOKENS_LIMIT = 8000
        private const val MIN_TEMPERATURE = 0.0f
        private const val MAX_TEMPERATURE = 2.0f
    }
    fun execute(actionName: String, input: JSONObject) = when (actionName) {
        "llm.prompt" -> prompt(input)
        else -> errorResult("Unknown action: $actionName")
    }
    private fun successResult(data: Map<String, Any?> = emptyMap()) = JSONObject().apply { put("success", true); data.forEach { (k, v) -> put(k, v) } }
    private fun errorResult(message: String) = JSONObject().apply { put("success", false); put("error", message) }
    private fun prompt(input: JSONObject): JSONObject {
        val systemPrompt = input.optString("systemPrompt", null) ?: return errorResult("Missing required field: systemPrompt")
        val userPrompt = input.optString("userPrompt", null) ?: return errorResult("Missing required field: userPrompt")
        if (systemPrompt.isBlank()) return errorResult("systemPrompt cannot be empty")
        if (userPrompt.isBlank()) return errorResult("userPrompt cannot be empty")
        val temperature = input.optDouble("temperature", 0.7).toFloat()
        if (temperature !in MIN_TEMPERATURE..MAX_TEMPERATURE) return errorResult("temperature must be between $MIN_TEMPERATURE and $MAX_TEMPERATURE")
        val maxTokens = input.optInt("maxTokens", 256)
        if (maxTokens !in 1..MAX_TOKENS_LIMIT) return errorResult("maxTokens must be between 1 and $MAX_TOKENS_LIMIT")
        return runBlocking { generateResponse(systemPrompt, userPrompt, temperature, maxTokens) }.fold(
            onSuccess = { successResult(mapOf("response" to it)) },
            onFailure = { errorResult(it.message ?: "LLM request failed") }
        )
    }
    private suspend fun generateResponse(systemPrompt: String, userPrompt: String, temperature: Float = 0.7f, maxTokens: Int = 256) = withContext(Dispatchers.IO) {
        try {
            makeApiCall(LlmRequest(MODEL, listOf(Message("system", systemPrompt), Message("user", userPrompt)), temperature, maxTokens, false))
                .choices.firstOrNull()?.message?.content?.takeIf { it.isNotBlank() }?.let { Result.success(it) }
                ?: Result.failure(Exception("Empty response from API"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    private fun makeApiCall(request: LlmRequest) = (URL(API_URL).openConnection() as HttpURLConnection).run {
        try {
            apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer ${BuildConfig.GROQ_API_KEY}")
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                doOutput = true
            }
            OutputStreamWriter(outputStream).use { it.write(buildRequestJson(request)); it.flush() }
            if (responseCode != HttpURLConnection.HTTP_OK) {
                throw Exception("API error $responseCode: ${errorStream?.bufferedReader()?.use { it.readText() } ?: "No error body"}")
            }
            parseResponse(BufferedReader(InputStreamReader(inputStream)).use { it.readText() })
        } finally {
            disconnect()
        }
    }
    private fun buildRequestJson(request: LlmRequest) = JSONObject().apply {
        put("model", request.model)
        put("temperature", request.temperature)
        put("max_tokens", request.max_tokens)
        put("stream", request.stream)
        put("messages", JSONArray().apply { request.messages.forEach { msg -> put(JSONObject().apply { put("role", msg.role); put("content", msg.content) }) } })
    }.toString()
    private fun parseResponse(responseBody: String) = JSONObject(responseBody).let { json ->
        LlmResponse(
            id = json.getString("id"),
            choices = List(json.getJSONArray("choices").length()) { i ->
                json.getJSONArray("choices").getJSONObject(i).let { choice ->
                    com.ambientai.data.Choice(
                        message = choice.getJSONObject("message").let { Message(it.getString("role"), it.getString("content")) },
                        finish_reason = choice.optString("finish_reason", null)
                    )
                }
            },
            usage = json.optJSONObject("usage")?.let { com.ambientai.data.Usage(it.getInt("prompt_tokens"), it.getInt("completion_tokens"), it.getInt("total_tokens")) }
        )
    }
}
