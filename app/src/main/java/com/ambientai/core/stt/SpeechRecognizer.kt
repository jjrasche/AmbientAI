package com.ambientai.core.stt

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer as AndroidSpeechRecognizer
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Handles speech-to-text with audio recording using RecognizerIntent GET_AUDIO extras.
 * Uses undocumented Google Keep approach to get audio back from recognition.
 */
class SpeechRecognizer(
    private val context: Context,
    private val onPartialTranscript: (text: String) -> Unit,
    private val onTranscriptReady: (text: String, audioFilePath: String) -> Unit,
    private val onError: (errorCode: Int) -> Unit
) {
    private var speechRecognizer: AndroidSpeechRecognizer? = null
    private var pauseDetectionJob: Job? = null

    private var lastResultTime = 0L
    private var currentTranscript = StringBuilder()
    private var isRecording = false

    companion object {
        private const val TAG = "SpeechRecognizer"
        private const val PAUSE_THRESHOLD_MS = 2000L // 2 seconds of silence triggers chunk
    }

    /**
     * Initialize the recognizer. Call before start().
     */
    fun initialize() {
        // Check permissions
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

    /**
     * Start listening and recording.
     */
    fun start() {
        if (isRecording) {
            Log.w(TAG, "Already recording")
            return
        }

        currentTranscript.clear()
        lastResultTime = System.currentTimeMillis()

        // Start speech recognition with GET_AUDIO extras
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

            // Undocumented extras to get audio back (Google Keep approach)
            putExtra("android.speech.extra.GET_AUDIO_FORMAT", "audio/AMR")
            putExtra("android.speech.extra.GET_AUDIO", true)
        }

        speechRecognizer?.startListening(intent)
        isRecording = true

        // Start pause detection
        startPauseDetection()

        Log.d(TAG, "Started listening")
    }

    /**
     * Stop listening and save the transcript.
     */
    fun stop() {
        if (!isRecording) return

        isRecording = false
        pauseDetectionJob?.cancel()
        speechRecognizer?.stopListening()

        Log.d(TAG, "Stopped listening")
    }

    /**
     * Clean up resources.
     */
    fun cleanup() {
        stop()
        speechRecognizer?.destroy()
        speechRecognizer = null
        Log.d(TAG, "Cleaned up")
    }

    private fun startPauseDetection() {
        pauseDetectionJob = CoroutineScope(Dispatchers.Default).launch {
            while (isRecording && coroutineContext.isActive) {
                delay(500) // Check every 500ms

                val timeSinceLastResult = System.currentTimeMillis() - lastResultTime

                // If we have transcript and enough pause time
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
            // Note: Audio path will be set in onResults when we get the URI
            stop()
        }
    }

    private fun copyAudioFromUri(uri: Uri): String {
        val audioDir = File(context.filesDir, "audio")
        audioDir.mkdirs()
        val outputFile = File(audioDir, "${UUID.randomUUID()}.amr")

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(outputFile).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Audio copied from URI to: ${outputFile.absolutePath}")
            return outputFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy audio from URI", e)
            return ""
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

        override fun onRmsChanged(rmsdB: Float) {
            // Could use for volume-based pause detection
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d(TAG, "End of speech")
        }

        override fun onError(error: Int) {
            Log.e(TAG, "Recognition error: $error")

            // SpeechRecognizer errors require restart
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

                // Try to get audio URI (undocumented extras)
                val audioUri = try {
                    results.getParcelable<Uri>("android.speech.extra.GET_AUDIO")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to get audio URI from results", e)
                    null
                }

                val audioPath = if (audioUri != null) {
                    Log.d(TAG, "Got audio URI: $audioUri")
                    copyAudioFromUri(audioUri)
                } else {
                    Log.w(TAG, "No audio URI in results - GET_AUDIO not supported")
                    ""
                }

                val text = currentTranscript.toString().trim()
                if (text.isNotEmpty()) {
                    stop()
                    onTranscriptReady(text, audioPath)
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(AndroidSpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val partial = matches[0]
                Log.d(TAG, "Partial result: $partial")

                // Notify UI with partial result
                onPartialTranscript(partial)

                lastResultTime = System.currentTimeMillis()
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}