package com.ambientai.core.stt

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer as AndroidSpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext

/**
 * Handles speech-to-text using Android's built-in SpeechRecognizer.
 * No audio recording - transcription only.
 */
class SpeechRecognizer(
    private val context: Context,
    private val onPartialTranscript: (text: String) -> Unit,
    private val onTranscriptReady: (text: String) -> Unit,
    private val onError: (errorCode: Int) -> Unit
) {
    private var speechRecognizer: AndroidSpeechRecognizer? = null
    private var pauseDetectionJob: Job? = null

    private var lastResultTime = 0L
    private var currentTranscript = StringBuilder()
    private var isRecording = false

    companion object {
        private const val TAG = "SpeechRecognizer"
        private const val PAUSE_THRESHOLD_MS = 2000L
    }

    fun initialize() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            return
        }

        if (!AndroidSpeechRecognizer.isRecognitionAvailable(context)) {
            Log.e(TAG, "Speech recognition not available")
            return
        }

        speechRecognizer = AndroidSpeechRecognizer.createSpeechRecognizer(context).apply {
            setRecognitionListener(recognitionListener)
        }

        Log.d(TAG, "SpeechRecognizer initialized")
    }

    fun start() {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return
        }

        currentTranscript.clear()
        lastResultTime = System.currentTimeMillis()

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }

        speechRecognizer?.startListening(intent)
        isRecording = true

        startPauseDetection()

        Log.d(TAG, "Started listening")
    }

    fun stop() {
        if (!isRecording) return

        isRecording = false
        pauseDetectionJob?.cancel()
        speechRecognizer?.stopListening()

        Log.d(TAG, "Stopped listening")
    }

    fun cleanup() {
        stop()
        speechRecognizer?.destroy()
        speechRecognizer = null
        Log.d(TAG, "Cleaned up")
    }

    private fun startPauseDetection() {
        pauseDetectionJob = CoroutineScope(Dispatchers.Default).launch {
            while (isRecording && coroutineContext.isActive) {
                delay(500)

                val timeSinceLastResult = System.currentTimeMillis() - lastResultTime

                if (currentTranscript.isNotEmpty() && timeSinceLastResult > PAUSE_THRESHOLD_MS) {
                    Log.d(TAG, "Pause detected - finalizing transcript")
                    finalizeTranscript()
                }
            }
        }
    }

    private fun finalizeTranscript() {
        if (currentTranscript.isEmpty()) return

        val text = currentTranscript.toString().trim()

        if (text.isNotEmpty()) {
            Log.d(TAG, "Transcript finalized: $text")
            stop()
        }
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d(TAG, "Ready for speech")
        }

        override fun onBeginningOfSpeech() {
            Log.d(TAG, "Speech started")
            lastResultTime = System.currentTimeMillis()
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "End of speech")
        }

        override fun onError(error: Int) {
            Log.e(TAG, "Recognition error: $error")

            if (isRecording) {
                stop()
                onError(error)
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(AndroidSpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val result = matches[0]
                Log.d(TAG, "Final result: $result")

                currentTranscript.append(result).append(" ")

                val text = currentTranscript.toString().trim()
                if (text.isNotEmpty()) {
                    stop()
                    onTranscriptReady(text)
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(AndroidSpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val partial = matches[0]
                Log.d(TAG, "Partial result: $partial")

                onPartialTranscript(partial)

                lastResultTime = System.currentTimeMillis()
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}