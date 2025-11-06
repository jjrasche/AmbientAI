package com.ambientai.core.wake

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import com.ambientai.BuildConfig

/**
 * Detects wake words using Porcupine.
 * Uses custom "Coral" wake word from assets.
 * Supports Bluetooth headset audio routing via SCO (legacy) or setCommunicationDevice (Android 12+).
 */
class WakeWordDetector(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit
) {
    private var porcupine: Porcupine? = null
    private var audioRecord: AudioRecord? = null
    private var audioManager: AudioManager? = null
    private val isListening = AtomicBoolean(false)
    private var detectionJob: Job? = null
    private var scoReceiver: BroadcastReceiver? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var scoConnected = false
    private var pendingStart = false
    private var useModernApi = false

    companion object {
        private const val TAG = "WakeWordDetector"
        private const val WAKE_WORD_FILE = "coral_en_android_v3_0_0.ppn"
    }

    /**
     * Initialize audio manager and register Bluetooth SCO receiver.
     * Call this before start().
     */
    fun initialize() {
        try {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

            // Register receiver for Bluetooth SCO state changes
            scoReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                    Log.d(TAG, "Bluetooth SCO state changed: $state")

                    when (state) {
                        AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                            Log.d(TAG, "Bluetooth SCO connected")
                            scoConnected = true

                            // If we were waiting to start, do it now
                            // Add small delay to ensure SCO is fully established
                            if (pendingStart) {
                                pendingStart = false
                                CoroutineScope(Dispatchers.Main).launch {
                                    delay(500) // Wait 500ms after SCO connects
                                    initializePorcupineAndStart()
                                }
                            }
                        }
                        AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                            Log.d(TAG, "Bluetooth SCO disconnected")
                            scoConnected = false
                        }
                        AudioManager.SCO_AUDIO_STATE_ERROR -> {
                            Log.e(TAG, "Bluetooth SCO error")
                            scoConnected = false

                            // Fallback: try to initialize without waiting for SCO
                            if (pendingStart) {
                                pendingStart = false
                                initializePorcupineAndStart()
                            }
                        }
                    }
                }
            }

            context.registerReceiver(
                scoReceiver,
                IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            )

            Log.d(TAG, "WakeWordDetector initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize", e)
            throw e
        }
    }

    /**
     * Extract wake word file from assets to internal storage.
     * Porcupine requires a file path, not an asset stream.
     */
    private fun extractAssetToFile(assetName: String): String {
        val file = File(context.filesDir, assetName)

        // Only extract if file doesn't exist or is outdated
        if (!file.exists() || file.length() == 0L) {
            context.assets.open(assetName).use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "Extracted $assetName to ${file.absolutePath}")
        }

        return file.absolutePath
    }

    /**
     * Initialize Porcupine and start listening.
     * Called after SCO connection is established (if Bluetooth is being used).
     */
    private fun initializePorcupineAndStart() {
        try {
            // Extract wake word file
            val modelPath = extractAssetToFile(WAKE_WORD_FILE)

            // Initialize Porcupine
            porcupine = Porcupine.Builder()
                .setAccessKey(BuildConfig.PICOVOICE_ACCESS_KEY)
                .setKeywordPath(modelPath)
                .build(context)

            Log.d(TAG, "Porcupine initialized with wake word: Coral")

            // Check for microphone permission
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG, "RECORD_AUDIO permission not granted")
                return
            }

            val porcupineInstance = porcupine ?: return

            // Log audio routing state
            Log.d(TAG, "Audio routing - SCO connected: $scoConnected, SCO on: ${audioManager?.isBluetoothScoOn}, Mode: ${audioManager?.mode}")

            // Try VOICE_COMMUNICATION better audio preprocessing for STT (echo cancellation, noise suppression)
            var audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION

            try {
                // Initialize AudioRecord
                audioRecord = AudioRecord(
                    audioSource,
                    porcupineInstance.sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    porcupineInstance.frameLength * 2
                )

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.w(TAG, "VOICE_COMMUNICATION failed, trying VOICE_RECOGNITION")
                    audioRecord?.release()

                    // Fallback to VOICE_RECOGNITION
                    audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
                    audioRecord = AudioRecord(
                        audioSource,
                        porcupineInstance.sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        porcupineInstance.frameLength * 2
                    )
                }

                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    Log.e(TAG, "AudioRecord failed to initialize with any source")
                    return
                }

                Log.d(TAG, "AudioRecord initialized with source: $audioSource")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to create AudioRecord", e)
                return
            }

            // Start listening
            isListening.set(true)
            audioRecord?.startRecording()

            // Run detection loop on IO dispatcher
            detectionJob = CoroutineScope(Dispatchers.IO).launch {
                detectWakeWord(porcupineInstance)
            }

            Log.d(TAG, "Started listening for wake word (SCO: $scoConnected)")
        } catch (e: PorcupineException) {
            Log.e(TAG, "Failed to initialize Porcupine", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start AudioRecord", e)
            stop()
        }
    }

    /**
     * Start listening for wake word.
     * Attempts to enable Bluetooth via modern API (Android 12+) or legacy SCO.
     */
    fun start() {
        if (isListening.get()) {
            Log.w(TAG, "Already listening")
            return
        }

        audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION

        // Try modern API first (Android 12+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (tryModernBluetoothApi()) {
                useModernApi = true
                // Modern API succeeded, AudioRecord will be created after device is set
                return
            }
        }

        // Fall back to legacy SCO API
        val bluetoothEnabled = audioManager?.isBluetoothScoAvailableOffCall == true

        if (bluetoothEnabled) {
            Log.d(TAG, "Using legacy Bluetooth SCO API")
            audioManager?.isBluetoothScoOn = true
            audioManager?.startBluetoothSco()

            pendingStart = true

            // Timeout for legacy API
            CoroutineScope(Dispatchers.Main).launch {
                delay(5000)

                if (pendingStart && !scoConnected) {
                    Log.w(TAG, "Bluetooth SCO timeout, starting without SCO")
                    pendingStart = false
                    initializePorcupineAndStart()
                }
            }
        } else {
            Log.d(TAG, "Bluetooth not available, using device mic")
            initializePorcupineAndStart()
        }
    }

    /**
     * Try to use modern setCommunicationDevice API (Android 12+).
     * Returns true if successful.
     */
    private fun tryModernBluetoothApi(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false

        try {
            val devices = audioManager?.availableCommunicationDevices ?: return false

            // Look for Bluetooth headset
            val bluetoothDevice = devices.firstOrNull { device ->
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }

            if (bluetoothDevice == null) {
                Log.d(TAG, "No Bluetooth communication device found")
                return false
            }

            Log.d(TAG, "Found Bluetooth device: ${bluetoothDevice.productName}, type: ${bluetoothDevice.type}")

            // Register callback to monitor device changes
            audioDeviceCallback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    Log.d(TAG, "Audio devices added: ${addedDevices.size}")
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    Log.d(TAG, "Audio devices removed: ${removedDevices.size}")
                }
            }
            audioManager?.registerAudioDeviceCallback(audioDeviceCallback, null)

            // Set the communication device
            val result = audioManager?.setCommunicationDevice(bluetoothDevice) ?: false

            if (result) {
                Log.d(TAG, "Successfully set Bluetooth communication device")

                // Wait briefly for routing to establish, then initialize
                CoroutineScope(Dispatchers.Main).launch {
                    delay(1000) // 1 second for modern API
                    initializePorcupineAndStart()
                }

                return true
            } else {
                Log.w(TAG, "Failed to set Bluetooth communication device")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in modern Bluetooth API", e)
            return false
        }
    }

    /**
     * Detection loop - reads audio frames and processes them with Porcupine.
     */
    private suspend fun detectWakeWord(porcupine: Porcupine) {
        val buffer = ShortArray(porcupine.frameLength)
        Log.d(TAG, "Detection loop started, buffer size: ${buffer.size}")

        while (isListening.get() && coroutineContext.isActive) {
            val numRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1

            if (numRead < 0) {
                Log.e(TAG, "AudioRecord read error: $numRead")
                break
            }

            try {
                val keywordIndex = porcupine.process(buffer)

                if (keywordIndex >= 0) {
                    Log.d(TAG, "Wake word detected: Coral!")
                    withContext(Dispatchers.Main) {
                        onWakeWordDetected()
                    }
                }
            } catch (e: PorcupineException) {
                Log.e(TAG, "Error processing audio frame", e)
            }
        }

        Log.d(TAG, "Detection loop exited")
    }

    /**
     * Stop listening for wake word.
     */
    fun stop() {
        isListening.set(false)
        pendingStart = false
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

        // Clean up modern API
        if (useModernApi && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                audioManager?.clearCommunicationDevice()
                Log.d(TAG, "Cleared communication device")
            } catch (e: Exception) {
                Log.w(TAG, "Error clearing communication device", e)
            }

            audioDeviceCallback?.let {
                try {
                    audioManager?.unregisterAudioDeviceCallback(it)
                } catch (e: Exception) {
                    Log.w(TAG, "Error unregistering audio device callback", e)
                }
            }
            audioDeviceCallback = null
        }

        // Stop legacy Bluetooth SCO
        audioManager?.stopBluetoothSco()
        audioManager?.isBluetoothScoOn = false

        // Unregister SCO receiver
        try {
            scoReceiver?.let { context.unregisterReceiver(it) }
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Receiver already unregistered")
        }
        scoReceiver = null

        // Clean up Porcupine
        porcupine?.delete()
        porcupine = null

        audioManager = null
        scoConnected = false
        useModernApi = false

        Log.d(TAG, "Cleaned up resources")
    }
}