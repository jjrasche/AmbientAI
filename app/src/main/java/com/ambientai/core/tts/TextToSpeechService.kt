package com.ambientai.core.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.*
import kotlin.coroutines.resume

/**
 * Service for text-to-speech playback.
 * Uses Android's built-in TTS engine.
 */
class TextToSpeechService(
    private val context: Context,
    private val onError: (errorCode: Int) -> Unit = {}
) {
    private var tts: TextToSpeech? = null
    private var isInitialized = false

    companion object {
        private const val TAG = "TextToSpeechService"
        private const val UTTERANCE_ID = "ambient_ai_utterance"
    }

    /**
     * Initialize TTS engine.
     * Call this before speak().
     */
    suspend fun initialize(): Boolean = suspendCancellableCoroutine { continuation ->
        tts = TextToSpeech(context) { status ->
            isInitialized = status == TextToSpeech.SUCCESS

            if (isInitialized) {
                tts?.language = Locale.US
                Log.d(TAG, "TTS initialized successfully")
            } else {
                Log.e(TAG, "TTS initialization failed with status: $status")
            }

            continuation.resume(isInitialized)
        }
    }

    /**
     * Speak text asynchronously.
     * @param text Text to speak
     * @return true if started speaking, false if failed
     */
    suspend fun speak(text: String): Boolean {
        if (!isInitialized || tts == null) {
            Log.e(TAG, "TTS not initialized")
            return false
        }

        if (text.isBlank()) {
            Log.w(TAG, "Empty text, skipping TTS")
            return false
        }

        return suspendCancellableCoroutine { continuation ->
            val listener = object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.d(TAG, "Started speaking: ${text.take(50)}...")
                }

                override fun onDone(utteranceId: String?) {
                    Log.d(TAG, "Finished speaking")
                    continuation.resume(true)
                }

                override fun onError(utteranceId: String?) {
                    Log.e(TAG, "TTS error")
                    onError(-1)
                    continuation.resume(false)
                }
            }

            tts?.setOnUtteranceProgressListener(listener)

            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)

            if (result == TextToSpeech.ERROR) {
                Log.e(TAG, "speak() returned ERROR")
                continuation.resume(false)
            }
        }
    }

    /**
     * Stop current speech.
     */
    fun stop() {
        tts?.stop()
        Log.d(TAG, "Stopped speaking")
    }

    /**
     * Clean up resources.
     */
    fun cleanup() {
        stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        Log.d(TAG, "Cleaned up TTS")
    }
}