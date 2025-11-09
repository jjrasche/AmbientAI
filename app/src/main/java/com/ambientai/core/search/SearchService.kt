package com.ambientai.core.search

import com.ambientai.BuildConfig
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class SearchService {
    companion object {
        private const val API_URL = "https://api.search.brave.com/res/v1/web/search"
        private const val TIMEOUT_MS = 10000
    }
    fun execute(actionName: String, input: JSONObject) = when (actionName) {
        "search.query" -> query(input)
        else -> throw Exception("Unknown action: $actionName")
    }
    private fun query(input: JSONObject): JSONObject {
        val query = input.optString("query", null) ?: throw Exception("Missing required field: query")
        if (query.isBlank()) throw Exception("Query cannot be empty")
        val numResults = input.optInt("numResults", 3).coerceIn(1, 10)
        val results = performSearch(query, numResults)
        return JSONObject(mapOf("query" to query, "results" to results.map(::resultToMap), "snippets" to formatSnippets(results)))
    }
    private fun performSearch(query: String, count: Int): List<SearchResult> {
        val url = URL("$API_URL?q=${URLEncoder.encode(query, "UTF-8")}&count=$count")
        return (url.openConnection() as HttpURLConnection).run {
            try {
                apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("X-Subscription-Token", BuildConfig.BRAVE_SEARCH_API_KEY)
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                }
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw Exception("API error $responseCode: ${errorStream?.bufferedReader()?.use { it.readText() } ?: "No error body"}")
                }
                parseResults(BufferedReader(InputStreamReader(inputStream)).use { it.readText() })
            } finally {
                disconnect()
            }
        }
    }
    private fun parseResults(json: String) = JSONObject(json).optJSONObject("web")?.optJSONArray("results")?.let { results ->
        List(results.length()) { i ->
            results.getJSONObject(i).let { SearchResult(it.optString("title", ""), it.optString("url", ""), it.optString("description", "")) }
        }
    } ?: emptyList()
    private fun formatSnippets(results: List<SearchResult>) =
        results.mapIndexed { index, result -> "[${index + 1}] ${result.title}\n${result.description}\nSource: ${result.url}" }.joinToString("\n\n")
    private fun resultToMap(result: SearchResult) = mapOf("title" to result.title, "url" to result.url, "description" to result.description)
    private data class SearchResult(val title: String, val url: String, val description: String)
}
