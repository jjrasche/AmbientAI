package com.ambientai.core.stt

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
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
 * Handles speech-to-text with pause detection for chunking.
 * Records audio simultaneously for storage.
 */
class SpeechRecognizer(
    private val context: Context,
    private val onPartialTranscript: (text: String) -> Unit,
    private val onTranscriptReady: (text: String, audioFilePath: String) -> Unit,
    private val onError: (errorCode: Int) -> Unit
) {
    private var speechRecognizer: AndroidSpeechRecognizer? = null
    private var audioRecord: AudioRecord? = null
    private var audioFile: File? = null
    private var recordingJob: Job? = null
    private var pauseDetectionJob: Job? = null

    private var lastResultTime = 0L
    private var currentTranscript = StringBuilder()
    private var isRecording = false

    companion object {
        private const val TAG = "SpeechRecognizer"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
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

        // Start audio recording
        startAudioRecording()

        // Start speech recognition
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
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
        stopAudioRecording()

        Log.d(TAG, "Stopped listening")
    }

    /**
     * Clean up resources.
     */
    fun cleanup() {
        stop()
        speechRecognizer?.destroy()
        speechRecognizer = null
        recordingJob?.cancel()
        Log.d(TAG, "Cleaned up")
    }

    private fun startAudioRecording() {
        // Check permission before starting
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            return
        }

        try {
            // Create audio file
            val audioDir = File(context.filesDir, "audio")
            audioDir.mkdirs()
            audioFile = File(audioDir, "${UUID.randomUUID()}.pcm")

            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT
            )

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                return
            }

            audioRecord?.startRecording()

            // Record audio to file on background thread
            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                writeAudioToFile()
            }

            Log.d(TAG, "Audio recording started: ${audioFile?.path}")
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for audio recording", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio recording", e)
        }
    }

    private suspend fun writeAudioToFile() {
        val buffer = ShortArray(1024)
        val outputStream = FileOutputStream(audioFile)

        try {
            while (isRecording && coroutineContext.isActive) {
                val numRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (numRead > 0) {
                    // Convert shorts to bytes
                    val bytes = ByteArray(numRead * 2)
                    for (i in 0 until numRead) {
                        bytes[i * 2] = (buffer[i].toInt() and 0xFF).toByte()
                        bytes[i * 2 + 1] = (buffer[i].toInt() shr 8 and 0xFF).toByte()
                    }
                    outputStream.write(bytes)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error writing audio", e)
        } finally {
            outputStream.close()
        }
    }

    private fun stopAudioRecording() {
        recordingJob?.cancel()
        audioRecord?.apply {
            if (state == AudioRecord.STATE_INITIALIZED) {
                stop()
            }
            release()
        }
        audioRecord = null
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
        val audioPath = audioFile?.absolutePath ?: ""

        if (text.isNotEmpty()) {
            Log.d(TAG, "Transcript finalized: $text")

            // Stop everything
            stop()

            // Notify callback
            onTranscriptReady(text, audioPath)
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
                lastResultTime = System.currentTimeMillis()
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