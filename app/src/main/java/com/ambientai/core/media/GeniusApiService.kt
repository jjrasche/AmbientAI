package com.ambientai.core.media

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GeniusApiService @Inject constructor() {
    companion object { private const val TAG = "GeniusApiService"; private const val BASE_URL = "https://api.genius.com" }
    private val client = OkHttpClient()
    private val apiToken = com.ambientai.BuildConfig.GENIUS_API_TOKEN
    data class SearchResult(val songId: Long, val title: String, val artist: String, val url: String)
    suspend fun search(query: String, maxResults: Int = 3): List<SearchResult> = withContext(Dispatchers.IO) {
        if (apiToken.isBlank()) { Log.e(TAG, "Genius API token not configured"); return@withContext emptyList() }
        runCatching {
            val url = "$BASE_URL/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
            val request = Request.Builder().url(url).header("Authorization", "Bearer $apiToken").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) { Log.e(TAG, "Search failed: ${response.code}"); return@withContext emptyList() }
                val json = JSONObject(response.body?.string() ?: "")
                val hits = json.getJSONObject("response").getJSONArray("hits")
                (0 until minOf(hits.length(), maxResults)).map { i ->
                    val result = hits.getJSONObject(i).getJSONObject("result")
                    SearchResult(songId = result.getLong("id"), title = result.getString("title"), artist = result.getJSONObject("primary_artist").getString("name"), url = result.getString("url"))
                }
            }
        }.getOrElse { e -> Log.e(TAG, "Search error", e); emptyList() }
    }
    suspend fun fetchLyrics(songId: Long): String? = withContext(Dispatchers.IO) {
        if (apiToken.isBlank()) { Log.e(TAG, "Genius API token not configured"); return@withContext null }
        runCatching {
            val url = "$BASE_URL/songs/$songId"
            val request = Request.Builder().url(url).header("Authorization", "Bearer $apiToken").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) { Log.e(TAG, "Fetch song failed: ${response.code}"); return@withContext null }
                val json = JSONObject(response.body?.string() ?: "")
                val song = json.getJSONObject("response").getJSONObject("song")
                val lyricsUrl = song.getString("url")
                scrapeLyrics(lyricsUrl)
            }
        }.getOrElse { e -> Log.e(TAG, "Fetch lyrics error", e); null }
    }
    private fun scrapeLyrics(url: String): String? = runCatching {
        val request = Request.Builder().url(url).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            val html = response.body?.string() ?: return@use null
            extractLyricsFromHtml(html)
        }
    }.getOrElse { e -> Log.e(TAG, "Scrape error", e); null }
    private fun extractLyricsFromHtml(html: String): String? {
        val lyricsRegex = """<div data-lyrics-container="true"[^>]*>(.*?)</div>""".toRegex(RegexOption.DOT_MATCHES_ALL)
        val matches = lyricsRegex.findAll(html)
        if (!matches.any()) return null
        val lyrics = matches.joinToString("\n\n") { match ->
            match.groupValues[1].replace("<br/>", "\n").replace("<br>", "\n").replace("""<[^>]+>""".toRegex(), "").replace("&quot;", "\"").replace("&amp;", "&").replace("&#x27;", "'").trim()
        }
        return lyrics.takeIf { it.isNotBlank() }
    }
}
