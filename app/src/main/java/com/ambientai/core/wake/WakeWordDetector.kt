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
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import com.ambientai.BuildConfig

class WakeWordDetector(private val context: Context, private val onWakeWordDetected: () -> Unit) {
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
        private const val WAKE_WORD_FILE = "coral_en_android_v3_0_0.ppn"
    }
    fun initialize() {
        try {
            audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            scoReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    when (intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)) {
                        AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                            scoConnected = true
                            if (pendingStart) {
                                pendingStart = false
                                CoroutineScope(Dispatchers.Main).launch { delay(500); initializePorcupineAndStart() }
                            }
                        }
                        AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> scoConnected = false
                        AudioManager.SCO_AUDIO_STATE_ERROR -> {
                            scoConnected = false
                            if (pendingStart) { pendingStart = false; initializePorcupineAndStart() }
                        }
                    }
                }
            }
            context.registerReceiver(scoReceiver, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
        } catch (e: Exception) { throw e }
    }
    private fun extractAssetToFile(assetName: String) = File(context.filesDir, assetName).also { file ->
        if (!file.exists() || file.length() == 0L) {
            context.assets.open(assetName).use { input -> FileOutputStream(file).use { output -> input.copyTo(output) } }
        }
    }.absolutePath
    private fun initializePorcupineAndStart() {
        try {
            porcupine = Porcupine.Builder().setAccessKey(BuildConfig.PICOVOICE_ACCESS_KEY).setKeywordPath(extractAssetToFile(WAKE_WORD_FILE)).build(context)
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            val porcupineInstance = porcupine ?: return
            var audioSource = MediaRecorder.AudioSource.VOICE_COMMUNICATION
            try {
                audioRecord = AudioRecord(audioSource, porcupineInstance.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, porcupineInstance.frameLength * 2)
                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                    audioRecord?.release()
                    audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION
                    audioRecord = AudioRecord(audioSource, porcupineInstance.sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, porcupineInstance.frameLength * 2)
                }
                if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) return
            } catch (e: Exception) { return }
            isListening.set(true)
            audioRecord?.startRecording()
            detectionJob = CoroutineScope(Dispatchers.IO).launch { detectWakeWord(porcupineInstance) }
        } catch (e: PorcupineException) {
        } catch (e: Exception) { stop() }
    }
    fun start() {
        if (isListening.get()) return
        audioManager?.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && tryModernBluetoothApi()) {
            useModernApi = true
            return
        }
        if (audioManager?.isBluetoothScoAvailableOffCall == true) {
            audioManager?.isBluetoothScoOn = true
            audioManager?.startBluetoothSco()
            pendingStart = true
            CoroutineScope(Dispatchers.Main).launch {
                delay(5000)
                if (pendingStart && !scoConnected) { pendingStart = false; initializePorcupineAndStart() }
            }
        } else {
            initializePorcupineAndStart()
        }
    }
    private fun tryModernBluetoothApi(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        try {
            val bluetoothDevice = (audioManager?.availableCommunicationDevices ?: return false).firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET } ?: return false
            audioDeviceCallback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {}
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {}
            }
            audioManager?.registerAudioDeviceCallback(audioDeviceCallback, null)
            return (audioManager?.setCommunicationDevice(bluetoothDevice) ?: false).also { result ->
                if (result) CoroutineScope(Dispatchers.Main).launch { delay(1000); initializePorcupineAndStart() }
            }
        } catch (e: Exception) { return false }
    }
    private suspend fun detectWakeWord(porcupine: Porcupine) {
        val buffer = ShortArray(porcupine.frameLength)
        while (isListening.get() && coroutineContext.isActive) {
            val numRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
            if (numRead < 0) break
            try {
                if (porcupine.process(buffer) >= 0) withContext(Dispatchers.Main) { onWakeWordDetected() }
            } catch (e: PorcupineException) {}
        }
    }
    fun stop() {
        isListening.set(false)
        pendingStart = false
        detectionJob?.cancel()
        detectionJob = null
        audioRecord?.apply { if (state == AudioRecord.STATE_INITIALIZED) stop(); release() }
        audioRecord = null
    }
    fun cleanup() {
        stop()
        if (useModernApi && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { audioManager?.clearCommunicationDevice() } catch (e: Exception) {}
            audioDeviceCallback?.let { try { audioManager?.unregisterAudioDeviceCallback(it) } catch (e: Exception) {} }
            audioDeviceCallback = null
        }
        audioManager?.stopBluetoothSco()
        audioManager?.isBluetoothScoOn = false
        try { scoReceiver?.let { context.unregisterReceiver(it) } } catch (e: IllegalArgumentException) {}
        scoReceiver = null
        porcupine?.delete()
        porcupine = null
        audioManager = null
        scoConnected = false
        useModernApi = false
    }
}
