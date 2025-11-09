package com.ambientai.core.wake

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import com.ambientai.BuildConfig

class WakeWordDetector(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit
) {
    private var porcupine: Porcupine? = null
    private var audioRecord: AudioRecord? = null
    private var audioManager: AudioManager? = null
    private val isListening = AtomicBoolean(false)
    private var detectionJob: Job? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var bluetoothDevice: AudioDeviceInfo? = null

    companion object {
        private const val WAKE_WORD_FILE = "coral_en_android_v3_0_0.ppn"
    }

    fun initialize() {
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        Log.d(TAG, "WakeWordDetector initialized")
    }

    fun start() {
        if (isListening.get()) {
            Log.w(TAG, "Already listening")
            return
        }

        setupBluetoothIfAvailable()

        CoroutineScope(Dispatchers.Main).launch {
            delay(500) // Brief delay for audio routing
            initializePorcupineAndStart()
        }
    }

    private fun setupBluetoothIfAvailable() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            Log.w(TAG, "Modern Bluetooth API requires Android 12+")
            return
        }

        val devices = audioManager?.availableCommunicationDevices ?: return
        bluetoothDevice = devices.firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }

        if (bluetoothDevice == null) {
            Log.d(TAG, "No Bluetooth device found, using phone mic")
            return
        }

        Log.d(TAG, "Found Bluetooth: ${bluetoothDevice?.productName}")

        audioDeviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                Log.d(TAG, "Audio devices added: ${addedDevices.size}")
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                Log.d(TAG, "Audio devices removed: ${removedDevices.size}")
            }
        }
        audioManager?.registerAudioDeviceCallback(audioDeviceCallback, null)

        val result = audioManager?.setCommunicationDevice(bluetoothDevice!!) ?: false
        if (result) {
            Log.d(TAG, "Set Bluetooth communication device")
        } else {
            Log.w(TAG, "Failed to set Bluetooth device")
            bluetoothDevice = null
        }
    }

    private fun initializePorcupineAndStart() {
        try {
            // Cleanup previous instance
            porcupine?.delete()

            val modelPath = extractAssetToFile(WAKE_WORD_FILE)

            porcupine = Porcupine.Builder()
                .setAccessKey(BuildConfig.PICOVOICE_ACCESS_KEY)
                .setKeywordPath(modelPath)
                .build(context)

            Log.d(TAG, "Porcupine initialized")

            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "RECORD_AUDIO permission not granted")
                return
            }

            val porcupineInstance = porcupine ?: return

            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                porcupineInstance.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                porcupineInstance.frameLength * 2
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialize")
                return
            }

            Log.d(TAG, "AudioRecord initialized (Bluetooth: ${bluetoothDevice != null})")

            isListening.set(true)
            audioRecord?.startRecording()

            detectionJob = CoroutineScope(Dispatchers.IO).launch {
                detectWakeWord(porcupineInstance)
            }

            Log.d(TAG, "Started listening")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start", e)
            stop()
        }
    }

    private fun extractAssetToFile(assetName: String): String {
        val file = File(context.filesDir, assetName)
        if (!file.exists() || file.length() == 0L) {
            context.assets.open(assetName).use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
        }
        return file.absolutePath
    }

    private suspend fun detectWakeWord(porcupine: Porcupine) {
        val buffer = ShortArray(porcupine.frameLength)

        while (isListening.get() && coroutineContext.isActive) {
            val numRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
            if (numRead < 0) break
            try {
                val keywordIndex = porcupine.process(buffer)
                if (keywordIndex >= 0) {
                    Log.d(TAG, "Wake word detected!")
                    withContext(Dispatchers.Main) {
                        onWakeWordDetected()
                    }
                }
            } catch (e: PorcupineException) {
                Log.e(TAG, "Error processing audio", e)
            }
        }
    }

    fun stop() {
        isListening.set(false)
        detectionJob?.cancel()
        detectionJob = null
        audioRecord?.apply { if (state == AudioRecord.STATE_INITIALIZED) stop(); release() }
        audioRecord = null
    }

    fun cleanup() {
        stop()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager?.clearCommunicationDevice()
        }

        audioDeviceCallback?.let {
            audioManager?.unregisterAudioDeviceCallback(it)
        }
        audioDeviceCallback = null
        bluetoothDevice = null

        porcupine?.delete()
        porcupine = null
        audioManager = null

        Log.d(TAG, "Cleaned up")
    }
}
