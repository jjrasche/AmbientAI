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
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "ambient_ai_voice_channel"
        const val ACTION_PAUSE_DETECTION = "com.ambientai.PAUSE_DETECTION"
        const val ACTION_RESUME_DETECTION = "com.ambientai.RESUME_DETECTION"
        @Volatile private var isRunning = false
        @Volatile private var isDetecting = false
        @Volatile private var isTtsSpeaking = false
        fun isServiceRunning() = isRunning
        fun isDetectionActive() = isDetecting
    }
    interface TranscriptUpdateListener {
        fun onPartialTranscript(text: String)
        fun onTranscriptSaved(transcript: Transcript)
    }
    inner class LocalBinder : Binder() {
        fun getService() = this@VoiceListeningService
    }
    fun registerListener(listener: TranscriptUpdateListener) = listeners.add(listener)
    fun unregisterListener(listener: TranscriptUpdateListener) = listeners.remove(listener)
    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, createNotification("Initializing..."), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, createNotification("Initializing..."))
        }
        transcriptRepository = TranscriptRepository()
        workflowRouter = WorkflowRouter()
        workflowExecutor = WorkflowExecutor(applicationContext)
        initializeComponents()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE_DETECTION -> { pauseDetection(); return START_STICKY }
            ACTION_RESUME_DETECTION -> { resumeDetection(); return START_STICKY }
        }
        if (!isDetecting) resumeDetection()
        return START_STICKY
    }
    override fun onBind(intent: Intent?) = binder
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        isDetecting = false
        wakeWordDetector?.cleanup()
        speechRecognizer?.cleanup()
        ttsService?.cleanup()
        serviceScope.cancel()
    }
    fun pauseDetection() {
        wakeWordDetector?.stop()
        isDetecting = false
        updateNotification("Paused (tap tile to resume)")
    }
    fun resumeDetection() {
        wakeWordDetector?.start()
        isDetecting = true
        updateNotification("Listening for wake word...")
    }
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Voice Listening", NotificationManager.IMPORTANCE_LOW).apply { description = "Ambient AI voice detection"; setShowBadge(false) }
            )
        }
    }
    private fun createNotification(text: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Ambient AI").setContentText(text).setSmallIcon(R.mipmap.ic_launcher)
        .addAction(
            if (isDetecting) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (isDetecting) "Pause" else "Resume",
            PendingIntent.getService(this, 0, Intent(this, VoiceListeningService::class.java).apply {
                action = if (isDetecting) ACTION_PAUSE_DETECTION else ACTION_RESUME_DETECTION
            }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        )
        .setPriority(NotificationCompat.PRIORITY_LOW).setOngoing(true).build()
    private fun updateNotification(text: String) = getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, createNotification(text))
    private fun initializeComponents() = serviceScope.launch {
        try {
            ttsService = TextToSpeechService(applicationContext, ::handleTtsError)
            if (!(ttsService?.initialize() ?: false)) return@launch
            wakeWordDetector = WakeWordDetector(applicationContext, ::handleWakeWord).also { it.initialize() }
            speechRecognizer = SpeechRecognizer(applicationContext, ::handlePartialTranscript, ::handleTranscript, ::handleSttError).also { it.initialize() }
            reloadWorkflows()
            resumeDetection()
        } catch (e: Exception) {}
    }
    fun reloadWorkflows() {
        workflowRouter?.loadWorkflows()
        workflowExecutor?.loadCompletionTriggers()
    }
    private fun handleWakeWord() {
        val wasSpeaking = isTtsSpeaking
        if (wasSpeaking) {
            ttsService?.stop()
            isTtsSpeaking = false
        } else {
            wakeWordDetector?.stop()
        }
        updateNotification("Listening...")
        serviceScope.launch { delay(if (wasSpeaking) 100 else 10); speechRecognizer?.start() }
    }
    private fun handlePartialTranscript(text: String) = listeners.forEach { it.onPartialTranscript(text) }
    private fun handleTranscript(text: String) {
        Transcript(text = text, audioFilePath = "", timestamp = System.currentTimeMillis(), excludeFromContext = false).also {
            transcriptRepository?.save(it)
            listeners.forEach { listener -> listener.onTranscriptSaved(it) }
            routeToWorkflow(text, it.id)
        }
    }
    private suspend fun speak(text: String) { isTtsSpeaking = true; ttsService?.speak(text); isTtsSpeaking = false }
    private fun routeToWorkflow(text: String, transcriptId: Long) = serviceScope.launch {
        try {
            updateNotification("Processing...")
            wakeWordDetector?.start()
            val match = workflowRouter?.route(text, transcriptId)
            if (match == null) {
                speak("No workflow matched.")
            } else {
                when (workflowExecutor?.execute(match)) {
                    is WorkflowResult.Failure -> speak("Workflow failed: ${(workflowExecutor?.execute(match) as? WorkflowResult.Failure)?.error}")
                    null -> speak("System error.")
                    else -> {}
                }
            }
            updateNotification("Listening for wake word...")
        } catch (e: MultipleMatchException) {
            wakeWordDetector?.start(); speak("Multiple workflows matched. Please be more specific."); updateNotification("Listening for wake word...")
        } catch (e: Exception) {
            wakeWordDetector?.start(); speak("Sorry, something went wrong."); updateNotification("Listening for wake word...")
        }
    }
    private fun handleSttError(errorCode: Int) = updateNotification(if (isDetecting) "Listening for wake word..." else "Paused (tap tile to resume)").also { if (isDetecting) wakeWordDetector?.start() }
    private fun handleTtsError(errorCode: Int) = Unit
}
