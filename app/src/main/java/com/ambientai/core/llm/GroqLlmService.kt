package com.ambientai.core.llm

import android.util.Log
import com.ambientai.BuildConfig
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
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroqLlmService @Inject constructor() {
    private var lastRequestTime = 0L
    private var requestsRemaining: Int? = null
    private var tokensRemaining: Int? = null
    private var requestsResetTime: Long? = null
    private var tokensResetTime: Long? = null
    private data class LlmRequest(val model: String, val messages: List<Message>, val temperature: Float, val max_tokens: Int, val stream: Boolean)
    private data class Message(val role: String, val content: String)
    private data class LlmResponse(val id: String, val choices: List<Choice>, val usage: Usage?)
    private data class Choice(val message: Message, val finish_reason: String?)
    private data class Usage(val prompt_tokens: Int, val completion_tokens: Int, val total_tokens: Int)

    companion object {
        private const val TAG = "GroqLlmService"
        private const val API_URL = "https://api.groq.com/openai/v1/chat/completions"
        private const val MODEL = "llama-3.1-8b-instant"
        private const val TIMEOUT_MS = 10000
        private const val MAX_TOKENS_LIMIT = 8000
        private const val MIN_TEMPERATURE = 0.0f
        private const val MAX_TEMPERATURE = 2.0f
        private const val QUOTA_LOW_THRESHOLD = 5
        private const val MIN_SAFE_INTERVAL_MS = 2100L
    }
    fun execute(actionName: String, input: JSONObject) = when (actionName) { "llm.prompt" -> prompt(input); else -> errorResult("Unknown action: $actionName") }
    private fun successResult(data: Map<String, Any?> = emptyMap()) = JSONObject().apply { put("success", true); data.forEach { (k, v) -> put(k, v) } }
    private fun errorResult(message: String) = JSONObject().apply { put("success", false); put("error", message) }
    private fun prompt(input: JSONObject): JSONObject = input.optString("systemPrompt", null)?.takeIf { it.isNotBlank() }?.let { systemPrompt -> input.optString("userPrompt", null)?.takeIf { it.isNotBlank() }?.let { userPrompt -> input.optDouble("temperature", 0.7).toFloat().takeIf { it in MIN_TEMPERATURE..MAX_TEMPERATURE }?.let { temperature -> input.optInt("maxTokens", 256).takeIf { it in 1..MAX_TOKENS_LIMIT }?.let { maxTokens -> runBlocking { generateResponse(systemPrompt, userPrompt, temperature, maxTokens) }.fold(onSuccess = { successResult(mapOf("response" to it)) }, onFailure = { errorResult(it.message ?: "LLM request failed") }) } ?: errorResult("maxTokens must be between 1 and $MAX_TOKENS_LIMIT") } ?: errorResult("temperature must be between $MIN_TEMPERATURE and $MAX_TEMPERATURE") } ?: errorResult("userPrompt cannot be empty") } ?: errorResult("systemPrompt cannot be empty")
    private suspend fun generateResponse(systemPrompt: String, userPrompt: String, temperature: Float = 0.7f, maxTokens: Int = 256) = withContext(Dispatchers.IO) { runCatching { makeApiCall(LlmRequest(MODEL, listOf(Message("system", systemPrompt), Message("user", userPrompt)), temperature, maxTokens, false)).choices.firstOrNull()?.message?.content?.takeIf { it.isNotBlank() }?.let { Result.success(it) } ?: Result.failure(Exception("Empty response from API")) }.getOrElse { Result.failure(it) } }
    private fun makeApiCall(request: LlmRequest): LlmResponse {
        Log.d(TAG, "🔵 Making Groq API call...")
        applyIntelligentDelay()
        return (URL(API_URL).openConnection() as HttpURLConnection).run { try { apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); setRequestProperty("Authorization", "Bearer ${BuildConfig.GROQ_API_KEY}"); connectTimeout = TIMEOUT_MS; readTimeout = TIMEOUT_MS; doOutput = true }; OutputStreamWriter(outputStream).use { it.write(buildRequestJson(request)); it.flush() }; lastRequestTime = System.currentTimeMillis(); val code = responseCode; Log.d(TAG, "🔵 Got response code: $code"); if (code != HttpURLConnection.HTTP_OK) throw Exception("API error $code: ${errorStream?.bufferedReader()?.use { it.readText() } ?: "No error body"}"); updateQuotaFromHeaders(); parseResponse(BufferedReader(InputStreamReader(inputStream)).use { it.readText() }) } finally { disconnect() } }
    }
    private fun applyIntelligentDelay() {
        val now = System.currentTimeMillis()
        tokensResetTime?.let { resetTime -> if (tokensRemaining == 0 && now < resetTime) { val waitMs = (resetTime - now).coerceAtLeast(0); Log.d(TAG, "⏳ Token quota exhausted, waiting ${waitMs}ms until reset"); Thread.sleep(waitMs); return } }
        requestsResetTime?.let { resetTime -> if (requestsRemaining == 0 && now < resetTime) { val waitMs = (resetTime - now).coerceAtLeast(0); Log.d(TAG, "⏳ Request quota exhausted, waiting ${waitMs}ms until reset"); Thread.sleep(waitMs); return } }
        val lowQuota = (tokensRemaining ?: Int.MAX_VALUE) < QUOTA_LOW_THRESHOLD || (requestsRemaining ?: Int.MAX_VALUE) < QUOTA_LOW_THRESHOLD
        if (lowQuota) { val elapsed = now - lastRequestTime; if (elapsed < MIN_SAFE_INTERVAL_MS) { val waitMs = MIN_SAFE_INTERVAL_MS - elapsed; Log.d(TAG, "⚠ Low quota (tokens: $tokensRemaining, requests: $requestsRemaining), applying safe delay ${waitMs}ms"); Thread.sleep(waitMs) } }
    }
    private fun HttpURLConnection.updateQuotaFromHeaders() {
        getHeaderField("x-ratelimit-remaining-requests")?.toIntOrNull()?.also { requestsRemaining = it }
        getHeaderField("x-ratelimit-remaining-tokens")?.toIntOrNull()?.also { tokensRemaining = it }
        getHeaderField("x-ratelimit-reset-requests")?.let { parseResetTime(it) }?.also { requestsResetTime = it }
        getHeaderField("x-ratelimit-reset-tokens")?.let { parseResetTime(it) }?.also { tokensResetTime = it }
        Log.d(TAG, "📊 Groq quota - Requests: $requestsRemaining (resets: $requestsResetTime), Tokens: $tokensRemaining (resets: $tokensResetTime)")
    }
    private fun parseResetTime(resetStr: String) = resetStr.toLongOrNull()?.let { it * 1000 } ?: resetStr.split("h", "m", "s").mapNotNull { it.toIntOrNull() }.let { parts -> when (parts.size) { 3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]; 2 -> parts[0] * 60 + parts[1]; 1 -> parts[0]; else -> null }?.let { System.currentTimeMillis() + (it * 1000) } }
    private fun buildRequestJson(request: LlmRequest) = JSONObject().apply { put("model", request.model); put("temperature", request.temperature); put("max_tokens", request.max_tokens); put("stream", request.stream); put("messages", JSONArray().apply { request.messages.forEach { msg -> put(JSONObject().apply { put("role", msg.role); put("content", msg.content) }) } }) }.toString()
    private fun parseResponse(responseBody: String) = JSONObject(responseBody).let { json -> LlmResponse(id = json.getString("id"), choices = List(json.getJSONArray("choices").length()) { i -> json.getJSONArray("choices").getJSONObject(i).let { choice -> Choice(message = choice.getJSONObject("message").let { Message(it.getString("role"), it.getString("content")) }, finish_reason = choice.optString("finish_reason", null)) } }, usage = json.optJSONObject("usage")?.let { Usage(it.getInt("prompt_tokens"), it.getInt("completion_tokens"), it.getInt("total_tokens")) }) }
}
