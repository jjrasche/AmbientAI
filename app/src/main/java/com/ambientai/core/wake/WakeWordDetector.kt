package com.ambientai.core.wake

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import com.ambientai.BuildConfig
/**
 * Detects wake words using Porcupine.
 * Uses built-in "porcupine" wake word initially.
 */
class WakeWordDetector(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit
) {
    private var porcupine: Porcupine? = null
    private var audioRecord: AudioRecord? = null
    private val isListening = AtomicBoolean(false)
    private var detectionJob: Job? = null

    companion object {
        private const val TAG = "WakeWordDetector"
    }

    /**
     * Initialize Porcupine with built-in wake word.
     * Call this before start().
     */
    fun initialize() {
        try {
            porcupine = Porcupine.Builder()
                .setAccessKey(BuildConfig.PICOVOICE_ACCESS_KEY)
                .setKeyword(Porcupine.BuiltInKeyword.PORCUPINE)
                .build(context)

            Log.d(TAG, "Porcupine initialized with built-in wake word")
        } catch (e: PorcupineException) {
            Log.e(TAG, "Failed to initialize Porcupine", e)
            throw e
        }
    }

    /**
     * Start listening for wake word.
     * Runs on background coroutine.
     */
    fun start() {
        if (isListening.get()) {
            Log.w(TAG, "Already listening")
            return
        }

        val porcupineInstance = porcupine ?: run {
            Log.e(TAG, "Porcupine not initialized")
            return
        }

        // Check for microphone permission
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted")
            return
        }

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                porcupineInstance.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                porcupineInstance.frameLength * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                return
            }

            isListening.set(true)
            audioRecord?.startRecording()

            // Run detection loop on IO dispatcher
            detectionJob = CoroutineScope(Dispatchers.IO).launch {
                detectWakeWord(porcupineInstance)
            }

            Log.d(TAG, "Started listening for wake word")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord", e)
            stop()
        }
    }

    private suspend fun detectWakeWord(porcupine: Porcupine) {
        val buffer = ShortArray(porcupine.frameLength)

        while (isListening.get() && coroutineContext.isActive) {
            val numRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1

            if (numRead < 0) {
                Log.e(TAG, "AudioRecord read error: $numRead")
                break
            }

            try {
                val keywordIndex = porcupine.process(buffer)

                if (keywordIndex >= 0) {
                    Log.d(TAG, "Wake word detected!")

                    // Notify on main thread
                    withContext(Dispatchers.Main) {
                        onWakeWordDetected()
                    }
                }
            } catch (e: PorcupineException) {
                Log.e(TAG, "Error processing audio frame", e)
            }
        }
    }

    /**
     * Stop listening for wake word.
     */
    fun stop() {
        isListening.set(false)
        detectionJob?.cancel()
        detectionJob = null

        audioRecord?.apply {
            if (state == AudioRecord.STATE_INITIALIZED) {
                stop()
            }
            release()
        }
        audioRecord = null

        Log.d(TAG, "Stopped listening")
    }

    /**
     * Clean up resources.
     * Call when done with detector.
     */
    fun cleanup() {
        stop()
        porcupine?.delete()
        porcupine = null
        Log.d(TAG, "Cleaned up resources")
    }
}