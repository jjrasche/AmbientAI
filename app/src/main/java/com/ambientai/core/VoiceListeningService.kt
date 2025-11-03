package com.ambientai.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
 * All workflow logic is handled by WorkflowRouter and WorkflowExecutor.
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

        transcriptRepository = TranscriptRepository(applicationContext)
        workflowRouter = WorkflowRouter(applicationContext)
        workflowExecutor = WorkflowExecutor(applicationContext)

        initializeComponents()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")
        wakeWordDetector?.start()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")

        wakeWordDetector?.cleanup()
        speechRecognizer?.cleanup()
        ttsService?.cleanup()
        workflowExecutor?.cleanup()
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
        serviceScope.launch {
            try {
                // Initialize TTS
                ttsService = TextToSpeechService(
                    context = applicationContext,
                    onError = ::handleTtsError
                )
                val ttsReady = ttsService?.initialize() ?: false

                if (!ttsReady) {
                    Log.e(TAG, "TTS initialization failed")
                }

                // Initialize wake word detector
                wakeWordDetector = WakeWordDetector(
                    context = applicationContext,
                    onWakeWordDetected = ::handleWakeWord
                )
                wakeWordDetector?.initialize()

                // Initialize speech recognizer
                speechRecognizer = SpeechRecognizer(
                    context = applicationContext,
                    onPartialTranscript = ::handlePartialTranscript,
                    onTranscriptReady = ::handleTranscript,
                    onError = ::handleSttError
                )
                speechRecognizer?.initialize()

                // Load workflows
                workflowRouter?.loadWorkflows()
                Log.d(TAG, "Workflows loaded")

                Log.d(TAG, "All components initialized")
                wakeWordDetector?.start()
                updateNotification("Listening for wake word...")
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
        Log.d(TAG, "Workflows reloaded")
    }

    private fun handleWakeWord() {
        Log.d(TAG, "Wake word detected - starting STT")
        wakeWordDetector?.stop()
        updateNotification("Listening...")
        serviceScope.launch {
            delay(200)
            speechRecognizer?.start()
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

        // Route to workflow
        routeToWorkflow(text)
    }

    /**
     * Route transcript to workflow using WorkflowRouter.
     * Execute matched workflow via WorkflowExecutor.
     * Handles all errors via TTS feedback.
     */
    private fun routeToWorkflow(text: String) {
        serviceScope.launch {
            try {
                updateNotification("Processing...")

                val match = workflowRouter?.route(text)

                if (match == null) {
                    // No workflow matched
                    Log.d(TAG, "No workflow matched")
                    ttsService?.speak("No workflow matched.")
                } else {
                    // Execute workflow
                    Log.d(TAG, "Executing workflow: ${match.definition.name}")

                    val result = workflowExecutor?.execute(match)

                    when (result) {
                        is WorkflowResult.Success -> {
                            Log.d(TAG, "Workflow completed successfully")
                        }
                        is WorkflowResult.Failure -> {
                            Log.e(TAG, "Workflow failed: ${result.error}")
                            ttsService?.speak("Workflow failed: ${result.error}")
                        }
                        null -> {
                            Log.e(TAG, "WorkflowExecutor is null")
                            ttsService?.speak("System error.")
                        }
                    }
                }

            } catch (e: MultipleMatchException) {
                // Multiple workflows matched
                Log.w(TAG, "Multiple workflows matched: ${e.matchedWorkflows}")
                val workflowList = e.matchedWorkflows.joinToString(", ")
                ttsService?.speak("Multiple workflows matched: $workflowList. Please be more specific.")

            } catch (e: Exception) {
                Log.e(TAG, "Error routing to workflow", e)
                ttsService?.speak("Sorry, something went wrong.")
            } finally {
                // Always resume wake word detection
                updateNotification("Listening for wake word...")
                wakeWordDetector?.start()
            }
        }
    }

    private fun handleSttError(errorCode: Int) {
        Log.e(TAG, "STT error: $errorCode")
        updateNotification("Listening for wake word...")
        wakeWordDetector?.start()
    }

    private fun handleTtsError(errorCode: Int) {
        Log.e(TAG, "TTS error: $errorCode")
    }
}