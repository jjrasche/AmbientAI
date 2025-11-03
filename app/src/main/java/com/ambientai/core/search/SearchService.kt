package com.ambientai.core.search

import android.util.Log
import com.ambientai.BuildConfig
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class SearchService {

    companion object {
        private const val TAG = "SearchService"
        private const val API_URL = "https://api.search.brave.com/res/v1/web/search"
        private const val TIMEOUT_MS = 10000
    }

    fun execute(actionName: String, input: JSONObject): JSONObject {
        return when (actionName) {
            "search.query" -> query(input)
            else -> throw Exception("Unknown action: $actionName")
        }
    }

    private fun query(input: JSONObject): JSONObject {
        val query = input.optString("query", null)
            ?: throw Exception("Missing required field: query")
        if (query.isBlank()) throw Exception("Query cannot be empty")

        val numResults = input.optInt("numResults", 3).coerceIn(1, 10)

        val results = performSearch(query, numResults)
        return JSONObject(mapOf(
            "query" to query,
            "results" to results.map { resultToMap(it) },
            "snippets" to formatSnippets(results)
        ))
    }

    private fun performSearch(query: String, count: Int): List<SearchResult> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = URL("$API_URL?q=$encodedQuery&count=$count")
        val connection = url.openConnection() as HttpURLConnection

        try {
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Subscription-Token", BuildConfig.BRAVE_SEARCH_API_KEY)
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
            }

            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }
                    ?: "No error body"
                throw Exception("API error $responseCode: $errorBody")
            }

            val responseBody = BufferedReader(InputStreamReader(connection.inputStream))
                .use { it.readText() }

            return parseResults(responseBody)
        } catch (e: Exception) {
            Log.e(TAG, "Search failed for query: $query", e)
            throw e
        } finally {
            connection.disconnect()
        }
    }

    private fun parseResults(json: String): List<SearchResult> {
        val response = JSONObject(json)
        val web = response.optJSONObject("web") ?: return emptyList()
        val results = web.optJSONArray("results") ?: return emptyList()

        return List(results.length()) { i ->
            val result = results.getJSONObject(i)
            SearchResult(
                title = result.optString("title", ""),
                url = result.optString("url", ""),
                description = result.optString("description", "")
            )
        }
    }

    private fun formatSnippets(results: List<SearchResult>): String {
        return results.mapIndexed { index, result ->
            "[${index + 1}] ${result.title}\n${result.description}\nSource: ${result.url}"
        }.joinToString("\n\n")
    }

    private fun resultToMap(result: SearchResult): Map<String, String> {
        return mapOf(
            "title" to result.title,
            "url" to result.url,
            "description" to result.description
        )
    }

    private data class SearchResult(
        val title: String,
        val url: String,
        val description: String
    )
}