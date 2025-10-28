package com.ambientai.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ambientai.R
import com.ambientai.core.stt.SpeechRecognizer
import com.ambientai.core.wake.WakeWordDetector
import kotlinx.coroutines.*

/**
 * Foreground service that runs continuously to:
 * 1. Listen for wake word via WakeWordDetector
 * 2. Trigger STT on wake word detection
 * 3. Save transcripts with audio files
 */
class VoiceListeningService : Service() {

    private var wakeWordDetector: WakeWordDetector? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val TAG = "VoiceListeningService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "ambient_ai_voice_channel"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Listening for wake word..."))

        initializeComponents()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")
        wakeWordDetector?.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")

        wakeWordDetector?.cleanup()
        speechRecognizer?.cleanup()
        serviceScope.cancel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Voice Listening",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ambient AI voice detection"
                setShowBadge(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ambient AI")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = createNotification(text)
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, notification)
    }

    private fun initializeComponents() {
        try {
            // Initialize wake word detector
            wakeWordDetector = WakeWordDetector(
                context = applicationContext,
                onWakeWordDetected = ::handleWakeWord
            )
            wakeWordDetector?.initialize()

            // Initialize speech recognizer
            speechRecognizer = SpeechRecognizer(
                context = applicationContext,
                onTranscriptReady = ::handleTranscript,
                onError = ::handleSttError
            )
            speechRecognizer?.initialize()

            Log.d(TAG, "All components initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize components", e)
        }
    }

    private fun handleWakeWord() {
        Log.d(TAG, "Wake word detected - starting STT")

        // Stop wake word detection while transcribing
        wakeWordDetector?.stop()

        // Update notification
        updateNotification("Listening...")

        // Start speech recognition
        speechRecognizer?.start()
    }

    private fun handleTranscript(text: String, audioFilePath: String) {
        Log.d(TAG, "Transcript received: $text")
        Log.d(TAG, "Audio saved to: $audioFilePath")

        // TODO: Phase 1 - Save to ObjectBox
        // val transcript = Transcript(
        //     text = text,
        //     audioFilePath = audioFilePath,
        //     timestamp = System.currentTimeMillis()
        // )
        // transcriptRepository.save(transcript)

        // Resume wake word detection
        updateNotification("Listening for wake word...")
        wakeWordDetector?.start()
    }

    private fun handleSttError(errorCode: Int) {
        Log.e(TAG, "STT error: $errorCode")

        // Resume wake word detection on error
        updateNotification("Listening for wake word...")
        wakeWordDetector?.start()
    }
}