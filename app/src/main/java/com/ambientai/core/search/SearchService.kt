package com.ambientai.core.search

import android.util.Log
import com.ambientai.BuildConfig
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SearchService @Inject constructor() {
    private var lastRequestTime = 0L
    private var quotaRemaining: Int? = null
    private var quotaLimit: Int? = null
    private var quotaResetTime: Long? = null

    companion object {
        private const val TAG = "SearchService"
        private const val API_URL = "https://api.search.brave.com/res/v1/web/search"
        private const val TIMEOUT_MS = 10000
        private const val QUOTA_LOW_THRESHOLD = 5
        private const val MIN_SAFE_INTERVAL_MS = 1100L
    }
    fun execute(actionName: String, input: JSONObject) = when (actionName) { "search.query" -> query(input); else -> throw Exception("Unknown action: $actionName") }
    private fun query(input: JSONObject) = input.optString("query", null)?.takeIf { it.isNotBlank() }?.let { query -> performSearch(query, input.optInt("numResults", 3).coerceIn(1, 10)).let { results -> JSONObject(mapOf("query" to query, "results" to results.map(::resultToMap), "snippets" to formatSnippets(results))) } } ?: throw Exception("${if (input.optString("query", null) == null) "Missing required field: query" else "Query cannot be empty"}")
    private fun performSearch(query: String, count: Int): List<SearchResult> {
        applyIntelligentDelay()
        return (URL("$API_URL?q=${URLEncoder.encode(query, "UTF-8")}&count=$count").openConnection() as HttpURLConnection).run { try { apply { requestMethod = "GET"; setRequestProperty("Accept", "application/json"); setRequestProperty("X-Subscription-Token", BuildConfig.BRAVE_SEARCH_API_KEY); connectTimeout = TIMEOUT_MS; readTimeout = TIMEOUT_MS }; lastRequestTime = System.currentTimeMillis(); if (responseCode != HttpURLConnection.HTTP_OK) throw Exception("API error $responseCode: ${errorStream?.bufferedReader()?.use { it.readText() } ?: "No error body"}"); updateQuotaFromHeaders(); parseResults(BufferedReader(InputStreamReader(inputStream)).use { it.readText() }) } finally { disconnect() } }
    }
    private fun applyIntelligentDelay() {
        val now = System.currentTimeMillis()
        quotaResetTime?.let { resetTime -> if (quotaRemaining == 0 && now < resetTime) { val waitMs = (resetTime - now).coerceAtLeast(0); Log.d(TAG, "⏳ Quota exhausted, waiting ${waitMs}ms until reset"); Thread.sleep(waitMs); return } }
        quotaRemaining?.let { remaining -> if (remaining < QUOTA_LOW_THRESHOLD) { val elapsed = now - lastRequestTime; if (elapsed < MIN_SAFE_INTERVAL_MS) { val waitMs = MIN_SAFE_INTERVAL_MS - elapsed; Log.d(TAG, "⚠ Low quota ($remaining remaining), applying safe delay ${waitMs}ms"); Thread.sleep(waitMs) } } }
    }
    private fun HttpURLConnection.updateQuotaFromHeaders() {
        getHeaderField("X-RateLimit-Remaining")?.toIntOrNull()?.also { quotaRemaining = it }
        getHeaderField("X-RateLimit-Limit")?.toIntOrNull()?.also { quotaLimit = it }
        getHeaderField("X-RateLimit-Reset")?.toLongOrNull()?.also { quotaResetTime = System.currentTimeMillis() + (it * 1000) }
        Log.d(TAG, "📊 Brave quota - Remaining: $quotaRemaining / $quotaLimit (resets in ${quotaResetTime?.let { (it - System.currentTimeMillis()) / 1000 }}s)")
    }
    private fun parseResults(json: String) = JSONObject(json).optJSONObject("web")?.optJSONArray("results")?.let { results -> List(results.length()) { i -> results.getJSONObject(i).let { SearchResult(it.optString("title", ""), it.optString("url", ""), it.optString("description", "")) } } } ?: emptyList()
    private fun formatSnippets(results: List<SearchResult>) = results.mapIndexed { index, result -> "[${index + 1}] ${result.title}\n${result.description}\nSource: ${result.url}" }.joinToString("\n\n")
    private fun resultToMap(result: SearchResult) = mapOf("title" to result.title, "url" to result.url, "description" to result.description)
    private data class SearchResult(val title: String, val url: String, val description: String)
}
