package com.ambientai.core.browser

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

@Singleton
class BrowserService @Inject constructor(@ApplicationContext private val context: Context) {
    companion object {
        private const val TAG = "BrowserService"
        private const val SESSION_TIMEOUT_MS = 10 * 60 * 1000L
    }
    private val sessions = ConcurrentHashMap<String, BrowserSession>()
    private data class BrowserSession(val webView: WebView, var lastAccess: Long = System.currentTimeMillis())
    fun execute(actionName: String, input: JSONObject): JSONObject = when (actionName) {
        "browser.navigate" -> navigate(input)
        "browser.click" -> click(input)
        "browser.scrape" -> scrape(input)
        "browser.extract" -> extract(input)
        "browser.screenshot" -> screenshot(input)
        "browser.type" -> type(input)
        "browser.waitFor" -> waitFor(input)
        "browser.executeJs" -> executeJs(input)
        "browser.close" -> close(input)
        else -> throw Exception("Unknown browser action: $actionName")
    }
    private fun getOrCreateSession(sessionId: String?): Pair<String, WebView> {
        cleanupExpiredSessions()
        val id = sessionId ?: "session_${System.currentTimeMillis()}"
        val session = sessions.getOrPut(id) {
            Log.d(TAG, "Creating new browser session: $id")
            BrowserSession(WebView(context).apply {
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        Log.d(TAG, "Page loaded: $url")
                    }
                }
            })
        }
        session.lastAccess = System.currentTimeMillis()
        return id to session.webView
    }
    private fun cleanupExpiredSessions() = sessions.entries.removeIf { (id, session) -> (System.currentTimeMillis() - session.lastAccess > SESSION_TIMEOUT_MS).also { expired -> if (expired) { Log.d(TAG, "Cleaning up expired session: $id"); session.webView.destroy() } } }
    private fun navigate(input: JSONObject) = input.optString("url", null)?.takeIf { it.isNotBlank() }?.let { url -> val (sessionId, webView) = getOrCreateSession(input.optString("sessionId", null)); suspendWebViewLoad(webView, url); JSONObject().apply { put("success", true); put("sessionId", sessionId); put("url", url); webView.title?.let { put("title", it) } } } ?: throw Exception("url is required")
    private fun click(input: JSONObject): JSONObject {
        val sessionId = input.optString("sessionId", null) ?: throw Exception("sessionId is required")
        val session = sessions[sessionId] ?: throw Exception("Session not found: $sessionId")
        val selector = input.optString("selector", null)
        val text = input.optString("text", null)
        if (selector == null && text == null) throw Exception("selector or text is required")
        val js = if (text != null) "document.evaluate(\"//*[contains(text(), '$text')]\", document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue.click();" else "document.querySelector('$selector').click();"
        executeJavaScript(session.webView, js)
        session.lastAccess = System.currentTimeMillis()
        return JSONObject().apply { put("success", true) }
    }
    private fun scrape(input: JSONObject): JSONObject {
        val sessionId = input.optString("sessionId", null) ?: throw Exception("sessionId is required")
        val selector = input.optString("selector", null) ?: throw Exception("selector is required")
        val session = sessions[sessionId] ?: throw Exception("Session not found: $sessionId")
        val js = "document.querySelector('$selector').textContent;"
        val text = executeJavaScript(session.webView, js)
        session.lastAccess = System.currentTimeMillis()
        return JSONObject().apply { put("success", true); put("text", text?.trim() ?: "") }
    }
    private fun extract(input: JSONObject): JSONObject {
        val sessionId = input.optString("sessionId", null) ?: throw Exception("sessionId is required")
        val session = sessions[sessionId] ?: throw Exception("Session not found: $sessionId")
        val htmlJs = "document.documentElement.outerHTML;"
        val textJs = "document.body.innerText;"
        val html = executeJavaScript(session.webView, htmlJs)
        val text = executeJavaScript(session.webView, textJs)
        session.lastAccess = System.currentTimeMillis()
        return JSONObject().apply { put("success", true); put("html", html ?: ""); put("text", text ?: "") }
    }
    private fun screenshot(input: JSONObject): JSONObject {
        val sessionId = input.optString("sessionId", null) ?: throw Exception("sessionId is required")
        val session = sessions[sessionId] ?: throw Exception("Session not found: $sessionId")
        val bitmap = Bitmap.createBitmap(session.webView.width, session.webView.height, Bitmap.Config.ARGB_8888)
        session.webView.draw(android.graphics.Canvas(bitmap))
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
        val base64 = Base64.encodeToString(outputStream.toByteArray(), Base64.DEFAULT)
        bitmap.recycle()
        session.lastAccess = System.currentTimeMillis()
        return JSONObject().apply { put("success", true); put("screenshot", base64) }
    }
    private fun type(input: JSONObject): JSONObject {
        val sessionId = input.optString("sessionId", null) ?: throw Exception("sessionId is required")
        val selector = input.optString("selector", null) ?: throw Exception("selector is required")
        val text = input.optString("text", null) ?: throw Exception("text is required")
        val session = sessions[sessionId] ?: throw Exception("Session not found: $sessionId")
        val js = "document.querySelector('$selector').value = '$text';"
        executeJavaScript(session.webView, js)
        session.lastAccess = System.currentTimeMillis()
        return JSONObject().apply { put("success", true) }
    }
    private fun waitFor(input: JSONObject): JSONObject {
        val sessionId = input.optString("sessionId", null) ?: throw Exception("sessionId is required")
        val selector = input.optString("selector", null) ?: throw Exception("selector is required")
        val timeout = input.optInt("timeout", 30000)
        val session = sessions[sessionId] ?: throw Exception("Session not found: $sessionId")
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < timeout) {
            val js = "document.querySelector('$selector') !== null;"
            val exists = executeJavaScript(session.webView, js)
            if (exists == "true") {
                session.lastAccess = System.currentTimeMillis()
                return JSONObject().apply { put("success", true) }
            }
            Thread.sleep(100)
        }
        throw Exception("Element not found within timeout: $selector")
    }
    private fun executeJs(input: JSONObject): JSONObject {
        val sessionId = input.optString("sessionId", null) ?: throw Exception("sessionId is required")
        val script = input.optString("script", null) ?: throw Exception("script is required")
        val session = sessions[sessionId] ?: throw Exception("Session not found: $sessionId")
        val result = executeJavaScript(session.webView, script)
        session.lastAccess = System.currentTimeMillis()
        return JSONObject().apply { put("success", true); put("result", result ?: "") }
    }
    private fun close(input: JSONObject): JSONObject {
        val sessionId = input.optString("sessionId", null) ?: throw Exception("sessionId is required")
        sessions.remove(sessionId)?.also { it.webView.destroy(); Log.d(TAG, "Session closed: $sessionId") } ?: throw Exception("Session not found: $sessionId")
        return JSONObject().apply { put("success", true) }
    }
    private fun suspendWebViewLoad(webView: WebView, url: String) = suspendCancellableCoroutine<Unit> { continuation ->
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                if (continuation.isActive) continuation.resume(Unit)
            }
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                if (continuation.isActive) continuation.resumeWithException(Exception("WebView error: $description"))
            }
        }
        webView.post { webView.loadUrl(url) }
    }
    private fun executeJavaScript(webView: WebView, script: String) = suspendCancellableCoroutine<String?> { continuation ->
        webView.post {
            webView.evaluateJavascript(script) { result -> if (continuation.isActive) continuation.resume(result) }
        }
    }
}
