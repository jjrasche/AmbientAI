package com.ambientai.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ambientai.R
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
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    companion object {
        private const val TAG = "VoiceListeningService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "ambient_ai_voice_channel"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        // Create notification channel (required for Android O+)
        createNotificationChannel()

        // Start as foreground service
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        // Initialize wake word detector
        initializeWakeWordDetector()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")

        // Start listening for wake word
        wakeWordDetector?.start()

        // Return START_STICKY so service restarts if killed
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")

        // Clean up resources
        wakeWordDetector?.cleanup()
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

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ambient AI")
            .setContentText("Listening for wake word...")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun initializeWakeWordDetector() {
        try {
            wakeWordDetector = WakeWordDetector(
                context = applicationContext,
                onWakeWordDetected = ::handleWakeWord
            )

            wakeWordDetector?.initialize()
            Log.d(TAG, "WakeWordDetector initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize WakeWordDetector", e)
        }
    }

    /**
     * Called when wake word is detected.
     * This is where we'll trigger STT and save the transcript.
     */
    private fun handleWakeWord() {
        Log.d(TAG, "Wake word detected - starting STT")

        serviceScope.launch {
            // TODO: Phase 1 - Implement STT
            // 1. Stop wake word detection temporarily
            // 2. Start SpeechRecognizer
            // 3. Wait for pause detection
            // 4. Get transcript text and audio file path

            // Stub for now
            val transcriptText = "[STT NOT IMPLEMENTED]"
            val audioFilePath = "[NO AUDIO FILE]"

            // TODO: Phase 1 - Save to ObjectBox
            // saveTranscript(transcriptText, audioFilePath)

            Log.d(TAG, "Transcript: $transcriptText")

            // Resume wake word detection
            wakeWordDetector?.start()
        }
    }

    // TODO: Phase 1 - Implement transcript saving
    // private fun saveTranscript(text: String, audioPath: String) {
    //     val transcript = Transcript(
    //         text = text,
    //         audioFilePath = audioPath,
    //         timestamp = System.currentTimeMillis()
    //     )
    //     transcriptRepository.save(transcript)
    // }
}