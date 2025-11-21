package com.ambientai.core.browser

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.View
import android.webkit.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.*
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
        private const val DEFAULT_WAIT_TIMEOUT_MS = 5000L
    }

    private data class BrowserSession(val webView: WebView, val handler: Handler, var lastAccess: Long = System.currentTimeMillis())
    private val sessions = ConcurrentHashMap<String, BrowserSession>()
    private val mainHandler = Handler(Looper.getMainLooper())

    init { scheduleCleanup() }

    suspend fun execute(actionName: String, input: JSONObject): JSONObject = when (actionName) {
        "browser.navigate" -> navigate(input.getString("url"))
        "browser.click" -> click(input.getString("sessionId"), input.optString("selector", null), input.optString("text", null))
        "browser.scrape" -> scrape(input.getString("sessionId"), input.getString("selector"))
        "browser.extract" -> extract(input.getString("sessionId"))
        "browser.screenshot" -> screenshot(input.getString("sessionId"))
        "browser.type" -> type(input.getString("sessionId"), input.getString("selector"), input.getString("text"))
        "browser.waitFor" -> waitFor(input.getString("sessionId"), input.getString("selector"), input.optLong("timeout", DEFAULT_WAIT_TIMEOUT_MS))
        "browser.executeJs" -> executeJs(input.getString("sessionId"), input.getString("script"))
        "browser.close" -> close(input.getString("sessionId"))
        else -> throw Exception("Unknown browser action: $actionName")
    }

    private suspend fun navigate(url: String): JSONObject = suspendCancellableCoroutine { cont ->
        val sessionId = UUID.randomUUID().toString()
        mainHandler.post {
            try {
                val webView = WebView(context).apply {
                    visibility = View.GONE
                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?) = consoleMessage?.let { Log.d(TAG, "Console [${it.messageLevel()}]: ${it.message()} (${it.sourceId()}:${it.lineNumber()})"); true } ?: true
                    }
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            Log.d(TAG, "✓ Page loaded: $url")
                            sessions[sessionId] = BrowserSession(this@apply, mainHandler)
                            cont.resume(JSONObject().apply { put("success", true); put("sessionId", sessionId); put("url", url) })
                        }
                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) = error?.let { if (request?.isForMainFrame == true) cont.resumeWithException(Exception("Failed to load: ${it.description}")) } ?: Unit
                    }
                    loadUrl(url)
                }
                cont.invokeOnCancellation { webView.destroy() }
            } catch (e: Exception) { cont.resumeWithException(e) }
        }
    }

    private suspend fun click(sessionId: String, selector: String?, text: String?): JSONObject = withSession(sessionId) { session ->
        val script = when {
            selector != null -> "document.querySelector('$selector')?.click(); true"
            text != null -> """
                (function() {
                    const elements = document.querySelectorAll('a, button, input[type="button"], input[type="submit"], [onclick]');
                    for (const el of elements) {
                        if (el.textContent.toLowerCase().includes('${text.lowercase()}') || el.value?.toLowerCase()?.includes('${text.lowercase()}')) {
                            el.click();
                            return true;
                        }
                    }
                    return false;
                })()
            """.trimIndent()
            else -> throw Exception("Either selector or text is required")
        }
        val result = evaluateJs(session, script)
        JSONObject().apply { put("success", result == "true"); put("sessionId", sessionId) }
    }

    private suspend fun scrape(sessionId: String, selector: String): JSONObject = withSession(sessionId) { session ->
        val script = "document.querySelector('$selector')?.textContent || ''"
        val result = evaluateJs(session, script)
        JSONObject().apply { put("success", true); put("sessionId", sessionId); put("text", result) }
    }

    private suspend fun extract(sessionId: String): JSONObject = withSession(sessionId) { session ->
        val html = evaluateJs(session, "document.documentElement.outerHTML")
        val bodyText = evaluateJs(session, "document.body.innerText")
        JSONObject().apply { put("success", true); put("sessionId", sessionId); put("html", html); put("bodyText", bodyText) }
    }

    private suspend fun screenshot(sessionId: String): JSONObject = withSession(sessionId) { session ->
        suspendCancellableCoroutine { cont ->
            session.handler.post {
                try {
                    val bitmap = Bitmap.createBitmap(session.webView.width.takeIf { it > 0 } ?: 1080, session.webView.height.takeIf { it > 0 } ?: 1920, Bitmap.Config.ARGB_8888)
                    val canvas = android.graphics.Canvas(bitmap)
                    session.webView.draw(canvas)
                    val stream = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
                    val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                    bitmap.recycle()
                    cont.resume(JSONObject().apply { put("success", true); put("sessionId", sessionId); put("image", base64) })
                } catch (e: Exception) { cont.resumeWithException(e) }
            }
        }
    }

    private suspend fun type(sessionId: String, selector: String, text: String): JSONObject = withSession(sessionId) { session ->
        val escapedText = text.replace("'", "\\'")
        val script = """
            (function() {
                const el = document.querySelector('$selector');
                if (!el) return false;
                el.focus();
                el.value = '$escapedText';
                el.dispatchEvent(new Event('input', { bubbles: true }));
                el.dispatchEvent(new Event('change', { bubbles: true }));
                return true;
            })()
        """.trimIndent()
        val result = evaluateJs(session, script)
        JSONObject().apply { put("success", result == "true"); put("sessionId", sessionId) }
    }

    private suspend fun waitFor(sessionId: String, selector: String, timeout: Long): JSONObject = withSession(sessionId) { session ->
        withTimeout(timeout) {
            var found = false
            while (!found) {
                val result = evaluateJs(session, "document.querySelector('$selector') !== null")
                if (result == "true") { found = true } else { kotlinx.coroutines.delay(100) }
            }
            JSONObject().apply { put("success", true); put("sessionId", sessionId) }
        }
    }

    private suspend fun executeJs(sessionId: String, script: String): JSONObject = withSession(sessionId) { session ->
        val result = evaluateJs(session, script)
        JSONObject().apply { put("success", true); put("sessionId", sessionId); put("result", result) }
    }

    private suspend fun close(sessionId: String): JSONObject = sessions.remove(sessionId)?.let { session ->
        mainHandler.post { session.webView.destroy() }
        Log.d(TAG, "✓ Closed session: $sessionId")
        JSONObject().apply { put("success", true); put("sessionId", sessionId) }
    } ?: throw Exception("Session not found: $sessionId")

    private suspend fun <T> withSession(sessionId: String, block: suspend (BrowserSession) -> T): T = sessions[sessionId]?.also { it.lastAccess = System.currentTimeMillis() }?.let { block(it) } ?: throw Exception("Session not found: $sessionId")

    private suspend fun evaluateJs(session: BrowserSession, script: String): String = suspendCancellableCoroutine { cont ->
        session.handler.post { session.webView.evaluateJavascript(script) { result -> cont.resume(result?.trim('"') ?: "") } }
    }

    private fun scheduleCleanup(): Unit = mainHandler.postDelayed({
        val now = System.currentTimeMillis()
        sessions.entries.filter { now - it.value.lastAccess > SESSION_TIMEOUT_MS }.forEach { (id, session) ->
            Log.d(TAG, "⏰ Auto-closing expired session: $id")
            sessions.remove(id)
            mainHandler.post { session.webView.destroy() }
        }
        scheduleCleanup()
    }, 60000).let { }
}
