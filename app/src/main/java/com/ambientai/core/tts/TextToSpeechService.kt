package com.ambientai.core.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.util.*
import kotlin.coroutines.resume

class TextToSpeechService(private val context: Context, private val onError: (errorCode: Int) -> Unit = {}) {
    private var tts: TextToSpeech? = null
    private var isInitialized = false

    companion object {
        private const val TAG = "TTS"
        private const val UTTERANCE_ID = "ambient_ai_utterance"
    }
    fun execute(actionName: String, input: JSONObject): JSONObject = when (actionName) { "tts.speak" -> speakAction(input); else -> errorResult("Unknown action: $actionName") }
    private fun successResult(data: Map<String, Any?> = emptyMap()) = JSONObject().apply { put("success", true); data.forEach { (k, v) -> put(k, v) } }
    private fun errorResult(message: String) = JSONObject().apply { put("success", false); put("error", message) }
    private fun speakAction(input: JSONObject) = input.optString("text", null)?.takeIf { it.isNotBlank() }?.let { text -> if (runBlocking { speak(text) }) successResult(mapOf("spoken" to text)) else errorResult("Failed to speak text") } ?: errorResult("${if (input.optString("text", null) == null) "Missing required field: text" else "Text cannot be empty"}")
    suspend fun initialize(): Boolean = suspendCancellableCoroutine { continuation -> tts = TextToSpeech(context) { status -> isInitialized = (status == TextToSpeech.SUCCESS).also { if (it) tts?.language = Locale.US }; continuation.resume(isInitialized) } }
    private fun applyPronunciationFixes(text: String) = text.replace(Regex("\\bJSON", RegexOption.IGNORE_CASE), "jay-sahn")
    suspend fun speak(text: String): Boolean { if (!isInitialized || tts == null) runBlocking { initialize() }; if (text.isBlank()) return false; return suspendCancellableCoroutine { continuation -> tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() { override fun onStart(utteranceId: String?) { Log.d(TAG, "▶ TTS STARTED: \"$text\"") }; override fun onDone(utteranceId: String?) { Log.d(TAG, "■ TTS ENDED: \"$text\""); continuation.resume(true) }; override fun onError(utteranceId: String?) { Log.e(TAG, "✖ TTS ERROR: \"$text\""); onError(-1); continuation.resume(false) } }); tts?.speak(applyPronunciationFixes(text), TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)?.let { if (it == TextToSpeech.ERROR) continuation.resume(false) } } }
    fun stop() { Log.d(TAG, "⏹ TTS STOPPED (interrupted)"); tts?.stop() }
    fun cleanup() { stop(); tts?.shutdown(); tts = null; isInitialized = false }
}
