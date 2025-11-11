package com.ambientai.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
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
import com.ambientai.data.repositories.ITranscriptRepository
import com.ambientai.workflow.MultipleMatchException
import com.ambientai.workflow.WorkflowExecutor
import com.ambientai.workflow.WorkflowResult
import com.ambientai.workflow.WorkflowRouter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class VoiceListeningService : Service() {
    private var wakeWordDetector: WakeWordDetector? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var ttsService: TextToSpeechService? = null

    @Inject lateinit var transcriptRepository: ITranscriptRepository
    @Inject lateinit var workflowRouter: WorkflowRouter
    @Inject lateinit var workflowExecutor: WorkflowExecutor
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val binder = LocalBinder()
    private val listeners = mutableSetOf<TranscriptUpdateListener>()
    private var pendingQuickStartWorkflowId: Long? = null

    companion object {
        private const val TAG = "VoiceService"
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
        workflowRouter.loadWorkflows()
        workflowExecutor.loadCompletionTriggers()
    }
    private fun handleWakeWord() = isTtsSpeaking.also { wasSpeaking ->
        Log.d(TAG, "⚡ WAKE WORD DETECTED")
        if (wasSpeaking) { Log.d(TAG, "⏹ Interrupting TTS"); ttsService?.stop(); isTtsSpeaking = false }
        wakeWordDetector?.stop()
        updateNotification("Listening...")
        val isBluetoothConnected = isBluetoothAudioConnected()
        Log.d(TAG, "🎧 Bluetooth audio: ${if (isBluetoothConnected) "CONNECTED" else "NOT CONNECTED"}")
        serviceScope.launch {
            if (isBluetoothConnected) {
                Log.d(TAG, "→ Bluetooth mode: Running TTS and STT in parallel")
                launch { speak("yes") }
                speechRecognizer?.start()
            } else {
                Log.d(TAG, "→ Phone mode: Waiting for TTS to complete before starting STT")
                speak("yes")
                delay(100)
                speechRecognizer?.start()
            }
        }
    }
    private fun isBluetoothAudioConnected(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices.any { device ->
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
        } else {
            audioManager.isBluetoothScoOn || audioManager.isBluetoothA2dpOn
        }
    }
    private fun handlePartialTranscript(text: String) = listeners.forEach { it.onPartialTranscript(text) }
    private fun handleTranscript(text: String) {
        Log.d(TAG, "📝 TRANSCRIPT: \"$text\"")
        Transcript(text = text, audioFilePath = "", timestamp = System.currentTimeMillis(), excludeFromContext = false).also {
            transcriptRepository.save(it)
            listeners.forEach { listener -> listener.onTranscriptSaved(it) }
            pendingQuickStartWorkflowId?.let { workflowId -> pendingQuickStartWorkflowId = null; executeWorkflowDirectly(workflowId, it.id) } ?: routeToWorkflow(text, it.id)
        }
    }
    private suspend fun speak(text: String) { isTtsSpeaking = true; ttsService?.speak(text); isTtsSpeaking = false }
    private fun routeToWorkflow(text: String, transcriptId: Long) = serviceScope.launch {
        try {
            Log.d(TAG, "🔀 ROUTING: \"$text\"")
            updateNotification("Processing...")
            wakeWordDetector?.start()
            val match = workflowRouter.route(text, transcriptId)
            if (match == null) {
                Log.w(TAG, "⚠ NO WORKFLOW MATCH")
                speak("No workflow matched.")
            } else {
                Log.d(TAG, "✓ MATCHED WORKFLOW: ${match.definition.name}")
                when (workflowExecutor.execute(match)) {
                    is WorkflowResult.Failure -> { Log.e(TAG, "✖ WORKFLOW FAILED: ${(workflowExecutor.execute(match) as? WorkflowResult.Failure)?.error}"); speak("Workflow failed: ${(workflowExecutor.execute(match) as? WorkflowResult.Failure)?.error}") }
                    null -> { Log.e(TAG, "✖ WORKFLOW ERROR: null result"); speak("System error.") }
                    else -> Log.d(TAG, "✓ WORKFLOW SUCCEEDED")
                }
            }
            updateNotification("Listening for wake word...")
        } catch (e: MultipleMatchException) {
            Log.w(TAG, "⚠ MULTIPLE MATCHES: ${e.message}")
            wakeWordDetector?.start(); speak("Multiple workflows matched. Please be more specific."); updateNotification("Listening for wake word...")
        } catch (e: Exception) {
            Log.e(TAG, "✖ WORKFLOW EXCEPTION: ${e.message}", e)
            wakeWordDetector?.start(); speak("Sorry, something went wrong."); updateNotification("Listening for wake word...")
        }
    }
    private fun handleSttError(errorCode: Int) = updateNotification(if (isDetecting) "Listening for wake word..." else "Paused (tap tile to resume)").also { if (isDetecting) wakeWordDetector?.start(); pendingQuickStartWorkflowId = null }
    private fun handleTtsError(errorCode: Int) = Unit
    fun startQuickStartRecording(workflowId: Long) { pendingQuickStartWorkflowId = workflowId; wakeWordDetector?.stop(); updateNotification("Quick Start: Listening..."); speechRecognizer?.start() }
    fun cancelQuickStart() { pendingQuickStartWorkflowId = null; speechRecognizer?.stop(); if (isDetecting) wakeWordDetector?.start(); updateNotification(if (isDetecting) "Listening for wake word..." else "Paused (tap tile to resume)") }
    fun executeWorkflowDirectly(workflowId: Long, transcriptId: Long) = serviceScope.launch {
        try {
            Log.d(TAG, "🎯 DIRECT EXECUTION: workflowId=$workflowId, transcriptId=$transcriptId")
            updateNotification("Executing workflow...")
            val transcript = if (transcriptId > 0) transcriptRepository.getById(transcriptId) else null
            val contextOverride = mutableMapOf<String, Any>("transcript" to (transcript?.text ?: ""), "transcriptId" to transcriptId)
            when (val result = workflowExecutor.executeById(workflowId, contextOverride)) {
                is WorkflowResult.Failure -> { Log.e(TAG, "✖ WORKFLOW FAILED: ${result.error}"); speak("Workflow failed: ${result.error}") }
                else -> Log.d(TAG, "✓ WORKFLOW SUCCEEDED")
            }
            updateNotification(if (isDetecting) "Listening for wake word..." else "Paused (tap tile to resume)")
            if (isDetecting) wakeWordDetector?.start()
        } catch (e: Exception) {
            Log.e(TAG, "✖ DIRECT EXECUTION EXCEPTION: ${e.message}", e)
            speak("Sorry, something went wrong.")
            updateNotification(if (isDetecting) "Listening for wake word..." else "Paused (tap tile to resume)")
            if (isDetecting) wakeWordDetector?.start()
        }
    }
}
