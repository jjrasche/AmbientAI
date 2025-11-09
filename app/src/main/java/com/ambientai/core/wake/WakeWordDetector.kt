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

class WakeWordDetector(private val context: Context, private val onWakeWordDetected: () -> Unit) {
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
    }
    fun start() {
        if (isListening.get()) return
        setupBluetoothIfAvailable()
        CoroutineScope(Dispatchers.Main).launch {
            delay(500)
            initializePorcupineAndStart()
        }
    }

    private fun setupBluetoothIfAvailable() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val devices = audioManager?.availableCommunicationDevices ?: return
        bluetoothDevice = devices.firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
        if (bluetoothDevice == null) return
        audioDeviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {}
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {}
        }
        audioManager?.registerAudioDeviceCallback(audioDeviceCallback, null)
        val result = audioManager?.setCommunicationDevice(bluetoothDevice!!) ?: false
        if (!result) bluetoothDevice = null
    }

    private fun initializePorcupineAndStart() {
        try {
            porcupine?.delete()
            porcupine = Porcupine.Builder()
                .setAccessKey(BuildConfig.PICOVOICE_ACCESS_KEY)
                .setKeywordPath(extractAssetToFile(WAKE_WORD_FILE))
                .build(context)
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
            porcupine ?: return
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                porcupine!!.sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                porcupine!!.frameLength * 2
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) return
            isListening.set(true)
            audioRecord?.startRecording()
            detectionJob = CoroutineScope(Dispatchers.IO).launch { detectWakeWord(porcupine!!) }
        } catch (e: Exception) { stop() }
    }
    private fun extractAssetToFile(assetName: String) = File(context.filesDir, assetName).also { file ->
        if (!file.exists() || file.length() == 0L) {
            context.assets.open(assetName).use { input ->
                FileOutputStream(file).use { output -> input.copyTo(output) }
            }
        }
    }.absolutePath

    private suspend fun detectWakeWord(porcupine: Porcupine) {
        val buffer = ShortArray(porcupine.frameLength)
        while (isListening.get() && coroutineContext.isActive) {
            val numRead = audioRecord?.read(buffer, 0, buffer.size) ?: -1
            if (numRead < 0) break
            try {
                val keywordIndex = porcupine.process(buffer)
                if (keywordIndex >= 0) withContext(Dispatchers.Main) { onWakeWordDetected() }
            } catch (e: PorcupineException) {}
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) audioManager?.clearCommunicationDevice()
        audioDeviceCallback?.let { audioManager?.unregisterAudioDeviceCallback(it) }
        audioDeviceCallback = null
        bluetoothDevice = null
        porcupine?.delete()
        porcupine = null
        audioManager = null
    }
}
