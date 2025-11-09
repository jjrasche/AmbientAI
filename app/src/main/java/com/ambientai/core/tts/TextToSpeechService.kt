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
        private const val UTTERANCE_ID = "ambient_ai_utterance"
    }

    fun execute(actionName: String, input: JSONObject): JSONObject = when (actionName) {
        "tts.speak" -> speakAction(input)
        else -> errorResult("Unknown action: $actionName")
    }
    private fun successResult(data: Map<String, Any?> = emptyMap()) = JSONObject().apply {
        put("success", true)
        data.forEach { (k, v) -> put(k, v) }
    }
    private fun errorResult(message: String) = JSONObject().apply {
        put("success", false)
        put("error", message)
    }

    private fun speakAction(input: JSONObject): JSONObject {
        val text = input.optString("text", null) ?: return errorResult("Missing required field: text")
        if (text.isBlank()) return errorResult("Text cannot be empty")
        val result = runBlocking { speak(text) }
        return if (result) successResult(mapOf("spoken" to text)) else errorResult("Failed to speak text")
    }
    suspend fun initialize(): Boolean = suspendCancellableCoroutine { continuation ->
        tts = TextToSpeech(context) { status ->
            isInitialized = status == TextToSpeech.SUCCESS
            if (isInitialized) {
                tts?.language = Locale.US
            } else {
                Log.e("TextToSpeechService", "TTS initialization failed with status: $status")
            }
            continuation.resume(isInitialized)
        }
    }
    private fun applyPronunciationFixes(text: String) = text.replace(Regex("\\bJSON", RegexOption.IGNORE_CASE), "jay-sahn")

    suspend fun speak(text: String): Boolean {
        if (!isInitialized || tts == null) runBlocking { initialize() }
        if (text.isBlank()) return false
        return suspendCancellableCoroutine { continuation ->
            val listener = object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { continuation.resume(true) }
                override fun onError(utteranceId: String?) {
                    Log.e("TextToSpeechService", "TTS error")
                    onError(-1)
                    continuation.resume(false)
                }
            }
            tts?.setOnUtteranceProgressListener(listener)
            val result = tts?.speak(applyPronunciationFixes(text), TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
            if (result == TextToSpeech.ERROR) {
                Log.e("TextToSpeechService", "speak() returned ERROR")
                continuation.resume(false)
            }
        }
    }
    fun stop() {
        tts?.stop()
    }
    fun cleanup() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }
}