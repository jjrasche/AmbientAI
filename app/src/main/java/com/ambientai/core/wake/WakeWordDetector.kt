package com.ambientai.core.wake

import ai.picovoice.porcupine.Porcupine
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.ambientai.BuildConfig
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

class WakeWordDetector(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit
) {
    private var porcupine: Porcupine? = null
    private var audioRecord: AudioRecord? = null
    private var audioManager: AudioManager? = null
    private val isListening = AtomicBoolean(false)
    private var detectionJob: Job? = null

    companion object {
        private const val TAG = "WakeWordDetector"
        private const val WAKE_WORD_FILE = "coral_en_android_v3_0_0.ppn"
    }

    fun initialize() {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        Log.d(TAG, "WakeWordDetector initialized")
    }

    fun start() {
        if (isListening.get()) return
        audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager?.availableCommunicationDevices
                ?.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                ?.let { device ->
                    Log.d(TAG, "Setting Bluetooth device: ${device.productName}")
                    if (audioManager?.setCommunicationDevice(device) == true) {
                        CoroutineScope(Dispatchers.Main).launch {
                            delay(1000)
                            initializePorcupine()
                        }
                        return
                    }
                }
        }
        initializePorcupine()
    }

    private fun initializePorcupine() {
        val modelPath = extractAsset(WAKE_WORD_FILE)
        porcupine = Porcupine.Builder()
            .setAccessKey(BuildConfig.PICOVOICE_ACCESS_KEY)
            .setKeywordPath(modelPath)
            .build(context)

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "Missing RECORD_AUDIO permission")
            return
        }

        val p = porcupine ?: return
        audioRecord = listOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION
        ).firstNotNullOfOrNull { source ->
            try {
                AudioRecord(source, p.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, p.frameLength * 2)
                    .takeIf { it.state == AudioRecord.STATE_INITIALIZED }
                    ?.also { Log.d(TAG, "AudioRecord initialized with source: $source") }
            } catch (e: Exception) {
                null
            }
        } ?: run {
            Log.e(TAG, "Failed to initialize AudioRecord")
            return
        }

        isListening.set(true)
        audioRecord?.startRecording()
        detectionJob = CoroutineScope(Dispatchers.IO).launch { detectLoop(p) }
        Log.d(TAG, "Started listening")
    }

    private suspend fun detectLoop(p: Porcupine) {
        val buffer = ShortArray(p.frameLength)
        while (isListening.get() && coroutineContext.isActive) {
            if (audioRecord?.read(buffer, 0, buffer.size) ?: -1 < 0) break
            if (p.process(buffer) >= 0) {
                withContext(Dispatchers.Main) { onWakeWordDetected() }
            }
        }
    }

    fun stop() {
        isListening.set(false)
        detectionJob?.cancel()
        audioRecord?.apply {
            if (state == AudioRecord.STATE_INITIALIZED) stop()
            release()
        }
        audioRecord = null
    }

    fun cleanup() {
        stop()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager?.clearCommunicationDevice()
        }
        porcupine?.delete()
        porcupine = null
        audioManager = null
    }

    private fun extractAsset(name: String) = File(context.filesDir, name).apply {
        if (!exists() || length() == 0L) {
            context.assets.open(name).use { it.copyTo(outputStream()) }
        }
    }.absolutePath
}