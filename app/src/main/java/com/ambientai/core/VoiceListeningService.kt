package com.ambientai.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.ambientai.R
import com.ambientai.core.stt.SpeechRecognizer
import com.ambientai.core.tts.TextToSpeechService
import com.ambientai.core.wake.WakeWordDetector
import com.ambientai.data.entities.Transcript
import com.ambientai.data.repositories.TranscriptRepository
import com.ambientai.workflow.MultipleMatchException
import com.ambientai.workflow.WorkflowExecutor
import com.ambientai.workflow.WorkflowResult
import com.ambientai.workflow.WorkflowRouter
import kotlinx.coroutines.*

/**
 * Foreground service that runs the voice pipeline:
 * Wake word → STT → Workflow routing → Execution → TTS
 *
 * Service stays alive continuously. Tile controls detection state (paused/active).
 */
class VoiceListeningService : Service() {

    private var wakeWordDetector: WakeWordDetector? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var transcriptRepository: TranscriptRepository? = null
    private var ttsService: TextToSpeechService? = null
    private var workflowRouter: WorkflowRouter? = null
    private var workflowExecutor: WorkflowExecutor? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val binder = LocalBinder()
    private val listeners = mutableSetOf<TranscriptUpdateListener>()
    companion object {
        private const val TAG = "VoiceListeningService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "ambient_ai_voice_channel"
        const val ACTION_PAUSE_DETECTION = "com.ambientai.PAUSE_DETECTION"
        const val ACTION_RESUME_DETECTION = "com.ambientai.RESUME_DETECTION"

        @Volatile
        private var isRunning = false
        @Volatile
        private var isDetecting = false
        @Volatile
        private var isTtsSpeaking = false


        fun isServiceRunning(): Boolean = isRunning
        fun isDetectionActive(): Boolean = isDetecting
    }

    interface TranscriptUpdateListener {
        fun onPartialTranscript(text: String)
        fun onTranscriptSaved(transcript: Transcript)
    }

    inner class LocalBinder : Binder() {
        fun getService(): VoiceListeningService = this@VoiceListeningService
    }

    fun registerListener(listener: TranscriptUpdateListener) {
        listeners.add(listener)
    }

    fun unregisterListener(listener: TranscriptUpdateListener) {
        listeners.remove(listener)
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
        isRunning = true

        createNotificationChannel()

        val notification = createNotification("Initializing...")

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        transcriptRepository = TranscriptRepository()
        workflowRouter = WorkflowRouter()
        workflowExecutor = WorkflowExecutor(applicationContext)

        initializeComponents()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started with action: ${intent?.action}")

        when (intent?.action) {
            ACTION_PAUSE_DETECTION -> {
                pauseDetection()
                return START_STICKY
            }
            ACTION_RESUME_DETECTION -> {
                resumeDetection()
                return START_STICKY
            }
        }

        // Default: start detection if not already detecting
        if (!isDetecting) {
            resumeDetection()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")

        isRunning = false
        isDetecting = false
        wakeWordDetector?.cleanup()
        speechRecognizer?.cleanup()
        ttsService?.cleanup()
        serviceScope.cancel()
    }

    /**
     * Pause wake word detection.
     * Service stays alive, microphone is released.
     */
    fun pauseDetection() {
        Log.d(TAG, "Pausing detection")
        wakeWordDetector?.stop()
        isDetecting = false
        updateNotification("Paused (tap tile to resume)")
    }

    /**
     * Resume wake word detection.
     * Service is already alive, just restart detector.
     */
    fun resumeDetection() {
        Log.d(TAG, "Resuming detection")
        wakeWordDetector?.start()
        isDetecting = true
        updateNotification("Listening for wake word...")
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
        // Create intent to toggle detection via notification
        val toggleIntent = Intent(this, VoiceListeningService::class.java).apply {
            action = if (isDetecting) ACTION_PAUSE_DETECTION else ACTION_RESUME_DETECTION
        }
        val togglePendingIntent = PendingIntent.getService(
            this,
            0,
            toggleIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val actionLabel = if (isDetecting) "Pause" else "Resume"
        val actionIcon = if (isDetecting) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ambient AI")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .addAction(actionIcon, actionLabel, togglePendingIntent)
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
        serviceScope.launch {
            try {
                // Initialize TTS FIRST and WAIT for it
                ttsService = TextToSpeechService(
                    context = applicationContext,
                    onError = ::handleTtsError
                )
                val ttsReady = ttsService?.initialize() ?: false

                if (!ttsReady) {
                    Log.e(TAG, "TTS initialization failed - service may not work properly")
                } else {
                    Log.d(TAG, "TTS initialized successfully")
                }

                // Now initialize other components
                wakeWordDetector = WakeWordDetector(
                    context = applicationContext,
                    onWakeWordDetected = ::handleWakeWord
                )
                wakeWordDetector?.initialize()

                speechRecognizer = SpeechRecognizer(
                    context = applicationContext,
                    onPartialTranscript = ::handlePartialTranscript,
                    onTranscriptReady = ::handleTranscript,
                    onRecognitionError = ::handleSttError
                )
                speechRecognizer?.initialize()
                reloadWorkflows()
                Log.d(TAG, "All components initialized")

                // Start detection by default
                resumeDetection()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize components", e)
            }
        }
    }

    /**
     * Reload workflows from database.
     * Call this when workflows are added/modified/deleted.
     */
    fun reloadWorkflows() {
        workflowRouter?.loadWorkflows()
        workflowExecutor?.loadCompletionTriggers()
        Log.d(TAG, "Workflows reloaded")
    }

    private fun handleWakeWord() {
        Log.d(TAG, "Wake word detected")

        if (isTtsSpeaking) {
            // Interrupt TTS
            Log.d(TAG, "Interrupting TTS")
            ttsService?.stop()
            isTtsSpeaking = false
            // Wake word already running, just start STT
            updateNotification("Listening...")
            serviceScope.launch {
                delay(100)
                speechRecognizer?.start()
            }
        } else {
            // Normal wake word flow
            wakeWordDetector?.stop()
            updateNotification("Listening...")
            serviceScope.launch {
                delay(10)
                speechRecognizer?.start()
            }
        }
    }

    private fun handlePartialTranscript(text: String) {
        Log.d(TAG, "Partial: $text")
        listeners.forEach { it.onPartialTranscript(text) }
    }

    private fun handleTranscript(text: String) {
        Log.d(TAG, "Transcript received: $text")

        // Save transcript
        val transcript = Transcript(
            text = text,
            audioFilePath = "",
            timestamp = System.currentTimeMillis(),
            excludeFromContext = false
        )
        transcriptRepository?.save(transcript)
        listeners.forEach { it.onTranscriptSaved(transcript) }

        // Route to workflow, passing transcript ID
        routeToWorkflow(text, transcript.id)
    }

    private fun routeToWorkflow(text: String, transcriptId: Long) {
        serviceScope.launch {
            try {
                updateNotification("Processing...")

                // Start wake word immediately for interruption support
                wakeWordDetector?.start()

                val match = workflowRouter?.route(text, transcriptId)

                if (match == null) {
                    isTtsSpeaking = true
                    ttsService?.speak("No workflow matched.")
                    isTtsSpeaking = false
                } else {
                    val result = workflowExecutor?.execute(match)
                    when (result) {
                        is WorkflowResult.Failure -> {
                            isTtsSpeaking = true
                            ttsService?.speak("Workflow failed: ${result.error}")
                            isTtsSpeaking = false
                        }
                        null -> {
                            isTtsSpeaking = true
                            ttsService?.speak("System error.")
                            isTtsSpeaking = false
                        }
                        else -> {}
                    }
                }

                updateNotification("Listening for wake word...")

            } catch (e: MultipleMatchException) {
                wakeWordDetector?.start()
                isTtsSpeaking = true
                ttsService?.speak("Multiple workflows matched. Please be more specific.")
                isTtsSpeaking = false
                updateNotification("Listening for wake word...")
            } catch (e: Exception) {
                wakeWordDetector?.start()
                isTtsSpeaking = true
                ttsService?.speak("Sorry, something went wrong.")
                isTtsSpeaking = false
                updateNotification("Listening for wake word...")
            }
        }
    }

    private fun handleSttError(errorCode: Int) {
        Log.e(TAG, "STT error: $errorCode")
        if (isDetecting) {
            updateNotification("Listening for wake word...")
            wakeWordDetector?.start()
        } else {
            updateNotification("Paused (tap tile to resume)")
        }
    }

    private fun handleTtsError(errorCode: Int) {
        Log.e(TAG, "TTS error: $errorCode")
    }
}