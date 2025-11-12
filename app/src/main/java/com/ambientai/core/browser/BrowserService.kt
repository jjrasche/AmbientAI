package com.ambientai.core.browser

import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BrowserService @Inject constructor() {
    companion object {
        private const val TAG = "BrowserService"
        private const val DEFAULT_BASE_URL = "http://127.0.0.1:3000"
        private const val TIMEOUT_MS = 30000
    }
    private val baseUrl = System.getProperty("browser.server.url") ?: DEFAULT_BASE_URL
    fun execute(actionName: String, input: JSONObject): JSONObject = when (actionName) {
        "browser.navigate" -> navigate(input)
        "browser.click" -> click(input)
        "browser.scrape" -> scrape(input)
        "browser.extract" -> extract(input)
        "browser.screenshot" -> screenshot(input)
        "browser.type" -> type(input)
        "browser.waitFor" -> waitFor(input)
        "browser.close" -> close(input)
        else -> throw Exception("Unknown browser action: $actionName")
    }
    private fun navigate(input: JSONObject) = input.optString("url", null)?.takeIf { it.isNotBlank() }?.let { url -> post("/browser/navigate", JSONObject().apply { put("url", url); input.optString("sessionId", null)?.let { put("sessionId", it) } }) } ?: throw Exception("url is required")
    private fun click(input: JSONObject): JSONObject {
        val sessionId = input.optString("sessionId", null) ?: throw Exception("sessionId is required")
        val selector = input.optString("selector", null)
        val text = input.optString("text", null)
        if (selector == null && text == null) throw Exception("selector or text is required")
        return post("/browser/click", JSONObject().apply { put("sessionId", sessionId); selector?.let { put("selector", it) }; text?.let { put("text", it) } })
    }
    private fun scrape(input: JSONObject): JSONObject {
        val sessionId = input.optString("sessionId", null) ?: throw Exception("sessionId is required")
        val selector = input.optString("selector", null) ?: throw Exception("selector is required")
        return post("/browser/scrape", JSONObject().apply { put("sessionId", sessionId); put("selector", selector) })
    }
    private fun extract(input: JSONObject): JSONObject {
        val sessionId = input.optString("sessionId", null) ?: throw Exception("sessionId is required")
        return post("/browser/extract", JSONObject().apply { put("sessionId", sessionId) })
    }
    private fun screenshot(input: JSONObject): JSONObject {
        val sessionId = input.optString("sessionId", null) ?: throw Exception("sessionId is required")
        val fullPage = input.optBoolean("fullPage", false)
        return post("/browser/screenshot", JSONObject().apply { put("sessionId", sessionId); put("fullPage", fullPage) })
    }
    private fun type(input: JSONObject): JSONObject {
        val sessionId = input.optString("sessionId", null) ?: throw Exception("sessionId is required")
        val selector = input.optString("selector", null) ?: throw Exception("selector is required")
        val text = input.optString("text", null) ?: throw Exception("text is required")
        return post("/browser/type", JSONObject().apply { put("sessionId", sessionId); put("selector", selector); put("text", text) })
    }
    private fun waitFor(input: JSONObject): JSONObject {
        val sessionId = input.optString("sessionId", null) ?: throw Exception("sessionId is required")
        val selector = input.optString("selector", null) ?: throw Exception("selector is required")
        val timeout = input.optInt("timeout", 30000)
        return post("/browser/waitFor", JSONObject().apply { put("sessionId", sessionId); put("selector", selector); put("timeout", timeout) })
    }
    private fun close(input: JSONObject): JSONObject {
        val sessionId = input.optString("sessionId", null) ?: throw Exception("sessionId is required")
        return post("/browser/close", JSONObject().apply { put("sessionId", sessionId) })
    }
    private fun post(endpoint: String, body: JSONObject) = (URL("$baseUrl$endpoint").openConnection() as HttpURLConnection).run {
        try {
            apply { requestMethod = "POST"; setRequestProperty("Content-Type", "application/json"); setRequestProperty("Accept", "application/json"); connectTimeout = TIMEOUT_MS; readTimeout = TIMEOUT_MS; doOutput = true }
            OutputStreamWriter(outputStream).use { it.write(body.toString()) }
            if (responseCode !in 200..299) throw Exception("HTTP $responseCode: ${errorStream?.bufferedReader()?.use { it.readText() } ?: "No error body"}")
            JSONObject(BufferedReader(InputStreamReader(inputStream)).use { it.readText() })
        } catch (e: Exception) {
            Log.e(TAG, "Browser request failed: $endpoint", e)
            throw Exception("Browser automation failed: ${e.message}")
        } finally { disconnect() }
    }
}
