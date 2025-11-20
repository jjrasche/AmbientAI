package com.ambientai.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import com.ambientai.R
import com.ambientai.core.audio.AudioFeedbackService
import com.ambientai.core.stt.DeepgramSttService
import com.ambientai.core.tts.TextToSpeechService
import com.ambientai.data.entities.GoldenDataset
import com.ambientai.data.entities.Transcript
import com.ambientai.data.repositories.IGoldenDatasetRepository
import com.ambientai.data.repositories.ITranscriptRepository
import com.ambientai.workflow.MultipleMatchException
import com.ambientai.workflow.WorkflowExecutor
import com.ambientai.workflow.WorkflowMatch
import com.ambientai.workflow.WorkflowResult
import com.ambientai.workflow.WorkflowRouter
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class VoiceListeningService : Service() {
    private var deepgramStt: DeepgramSttService? = null
    private var ttsService: TextToSpeechService? = null
    private var audioFeedback: AudioFeedbackService? = null

    @Inject lateinit var transcriptRepository: ITranscriptRepository
    @Inject lateinit var goldenDatasetRepository: IGoldenDatasetRepository
    @Inject lateinit var workflowRouter: WorkflowRouter
    @Inject lateinit var workflowExecutor: WorkflowExecutor
    @Inject lateinit var musicPlayerHandler: com.ambientai.core.music.MusicPlayerHandler
    @Inject lateinit var debugServer: com.ambientai.debug.DebugServer
    @Inject lateinit var workflowSeeder: com.ambientai.data.WorkflowSeeder
    @Inject lateinit var mediaIntelligence: com.ambientai.core.media.MediaIntelligenceManager
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val binder = LocalBinder()
    private val listeners = mutableSetOf<TranscriptUpdateListener>()
    private var pendingQuickStartWorkflowId: Long? = null
    private var wasMusicPlayingBeforeCommand = false
    private var currentAudioFilePath: String? = null
    private var lastPartialTranscript = ""
    private var workflowTriggeredDuringRecording = false
    private var recordingStartTime: Long = 0

    companion object {
        private const val TAG = "VoiceService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "ambient_ai_voice_channel"
        private const val ACTION_STOP_RECORDING = "com.ambientai.STOP_RECORDING"
        @Volatile private var isRunning = false
        @Volatile private var isTtsSpeaking = false
        fun isServiceRunning() = isRunning
    }
    interface TranscriptUpdateListener {
        fun onPartialTranscript(text: String)
        fun onWorkflowStarted(workflowName: String, transcript: String)
        fun onWorkflowCompleted(workflowName: String)
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
        debugServer.startServer()
        initializeComponents()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_RECORDING) {
            Log.d(TAG, "⏹ STOP from notification")
            stopRecordingManually()
        }
        return START_STICKY
    }
    override fun onBind(intent: Intent?) = binder
    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        audioFeedback?.cleanup()
        deepgramStt?.cleanup()
        ttsService?.cleanup()
        debugServer.stopServer()
        serviceScope.cancel()
    }
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Voice Listening", NotificationManager.IMPORTANCE_LOW).apply { description = "Ambient AI voice detection"; setShowBadge(false) }
            )
        }
    }
    private fun createNotification(text: String): Notification {
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Ambient AI").setContentText(text).setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW).setOngoing(true)
        if (deepgramStt?.isRecording() == true) {
            val stopIntent = Intent(this, VoiceListeningService::class.java).apply { action = ACTION_STOP_RECORDING }
            val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            builder.addAction(android.R.drawable.ic_delete, "Stop", stopPendingIntent)
        }
        return builder.build()
    }
    private fun updateNotification(text: String) = getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, createNotification(text))
    private fun initializeComponents() = serviceScope.launch {
        try {
            audioFeedback = AudioFeedbackService(applicationContext)
            ttsService = TextToSpeechService(applicationContext, ::handleTtsError)
            if (!(ttsService?.initialize() ?: false)) return@launch
            deepgramStt = DeepgramSttService(applicationContext, ::handlePartialTranscript, ::handleTranscript, ::handleSttError, ::handleAudioSaved, ::handleRecordingStopped)
            if (!deepgramStt!!.initialize()) { Log.e(TAG, "✖ DEEPGRAM STT INIT FAILED"); return@launch }
            mediaIntelligence.initialize()
            Log.d(TAG, "🔄 RESEEDING WORKFLOWS FROM CODE")
            workflowSeeder.reseedAll()
            reloadWorkflows()
            updateNotification("Ready - Long press power button to speak")
        } catch (e: Exception) { Log.e(TAG, "✖ INIT ERROR: ${e.message}") }
    }
    fun reloadWorkflows() {
        workflowRouter.loadWorkflows()
        workflowExecutor.loadCompletionTriggers()
    }
    private fun startListening() {
        recordingStartTime = System.currentTimeMillis()
        Log.d(TAG, "🎤 RECORDING STARTED at ${recordingStartTime}")
        checkAndPauseMusic()
        if (isTtsSpeaking) { Log.d(TAG, "⏹ Interrupting TTS"); ttsService?.stop(); isTtsSpeaking = false }
        lastPartialTranscript = ""
        workflowTriggeredDuringRecording = false
        updateNotification("Listening...")
        audioFeedback?.playStartTone()
        serviceScope.launch { deepgramStt?.start() }
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
    private fun isHeadphonesConnected(): Boolean {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                device.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_USB_HEADSET
            }
        } else {
            audioManager.isWiredHeadsetOn || audioManager.isBluetoothScoOn || audioManager.isBluetoothA2dpOn
        }
    }
    private fun handlePartialTranscript(text: String) {
        val elapsed = System.currentTimeMillis() - recordingStartTime
        val wordCount = text.split("\\s+".toRegex()).size
        Log.d(TAG, "📝 PARTIAL [${elapsed}ms, $wordCount words]: \"$text\"")
        listeners.forEach { it.onPartialTranscript(text) }
        if (!workflowTriggeredDuringRecording) {
            checkPartialForWorkflowTrigger(text, elapsed, wordCount)
        }
    }
    private fun checkPartialForWorkflowTrigger(partialText: String, elapsedMs: Long, wordCount: Int) = serviceScope.launch {
        if (elapsedMs < 600) {
            Log.d(TAG, "🔎 TOO EARLY: ${elapsedMs}ms, waiting for more audio")
            return@launch
        }
        if (wordCount > 4) {
            Log.d(TAG, "🔎 TOO LONG: $wordCount words, waiting for final transcript")
            return@launch
        }
        try {
            val matches = workflowRouter.route(partialText, -1, isPartial = true)
            val match = matches.firstOrNull()
            if (match != null && match.definition.name != "conversational_default") {
                Log.d(TAG, "🎯 INSTANT TRIGGER [${elapsedMs}ms]: ${match.definition.name} from \"$partialText\"")
                workflowTriggeredDuringRecording = true
                deepgramStt?.stop()
                executeWorkflowFromPartial(match, partialText)
            } else {
                Log.d(TAG, "🔎 No instant match, waiting for final transcript")
            }
        } catch (e: Exception) {
            Log.e(TAG, "✖ Partial routing error: ${e.message}")
        }
    }
    private suspend fun executeWorkflowFromPartial(match: WorkflowMatch, partialText: String) {
        listeners.forEach { it.onWorkflowStarted(match.definition.name, partialText) }
        val transcript = Transcript(text = partialText, timestamp = System.currentTimeMillis(), audioFilePath = currentAudioFilePath ?: "")
        val savedTranscript = transcriptRepository.save(transcript)
        Log.d(TAG, "💾 SAVED TRANSCRIPT: id=${savedTranscript.id}")
        updateGoldenDatasetWithWorkflow(savedTranscript.id, match.definition.name)
        val matchWithTranscriptId = workflowRouter.route(partialText, savedTranscript.id, isPartial = true).firstOrNull()
        val result = matchWithTranscriptId?.let { workflowExecutor.execute(it) }
        when (result) {
            is WorkflowResult.Failure -> Log.e(TAG, "✖ WORKFLOW FAILED: ${result.error}")
            else -> Log.d(TAG, "✓ WORKFLOW SUCCEEDED")
        }
        listeners.forEach { it.onWorkflowCompleted(match.definition.name) }
        listeners.forEach { it.onTranscriptSaved(savedTranscript) }
    }
    private fun handleAudioSaved(filePath: String) {
        Log.d(TAG, "💾 AUDIO SAVED CALLBACK: $filePath")
        currentAudioFilePath = filePath
    }
    private fun handleRecordingStopped() = serviceScope.launch {
        Log.d(TAG, "🔔 RECORDING STOPPED")
        audioFeedback?.playStopTone()
    }
    private fun handleTranscript(text: String) {
        if (workflowTriggeredDuringRecording) {
            Log.d(TAG, "📝 FINAL TRANSCRIPT (ignored, workflow already executed): \"$text\"")
            workflowTriggeredDuringRecording = false
            return
        }
        val elapsed = System.currentTimeMillis() - recordingStartTime
        val wordCount = text.split("\\s+".toRegex()).size
        Log.d(TAG, "📝 FINAL TRANSCRIPT [${elapsed}ms, $wordCount words]: \"$text\"")
        val audioPath = currentAudioFilePath ?: ""
        val transcript = Transcript(text = text, audioFilePath = audioPath, timestamp = System.currentTimeMillis(), excludeFromContext = false)
        transcriptRepository.save(transcript)
        listeners.forEach { it.onTranscriptSaved(transcript) }
        if (audioPath.isNotBlank()) {
            val dataset = GoldenDataset(audioFilePath = audioPath, transcript = text, timestamp = System.currentTimeMillis())
            goldenDatasetRepository.save(dataset)
            Log.d(TAG, "📊 GOLDEN DATASET SAVED: id=${dataset.id}")
        }
        currentAudioFilePath = null
        pendingQuickStartWorkflowId?.let { workflowId ->
            pendingQuickStartWorkflowId = null
            executeWorkflowDirectly(workflowId, transcript.id)
        } ?: routeToWorkflow(text, transcript.id)
    }
    private suspend fun speak(text: String) {
        isTtsSpeaking = true
        val headphonesConnected = isHeadphonesConnected()
        if (!headphonesConnected && deepgramStt?.isRecording() == true) {
            Log.d(TAG, "🔇 Pausing STT during TTS (no headphones)")
            deepgramStt?.stop()
        }
        ttsService?.speak(text)
        if (!headphonesConnected) {
            Log.d(TAG, "🎤 Resuming STT after TTS")
            deepgramStt?.start()
        }
        isTtsSpeaking = false
    }
    private fun routeToWorkflow(text: String, transcriptId: Long) = serviceScope.launch {
        try {
            Log.d(TAG, "🔀 ROUTING: \"$text\"")
            updateNotification("Processing...")
            val matches = workflowRouter.route(text, transcriptId)
            if (matches.isEmpty()) {
                Log.w(TAG, "⚠ NO WORKFLOW MATCH")
                speak("No workflow matched.")
            } else {
                Log.d(TAG, "✓ MATCHED ${matches.size} WORKFLOW(S): ${matches.map { it.definition.name }}")
                matches.forEachIndexed { index, match ->
                    if (index == 0) {
                        listeners.forEach { it.onWorkflowStarted(match.definition.name, text) }
                        updateGoldenDatasetWithWorkflow(transcriptId, match.definition.name)
                    }
                    val result = workflowExecutor.execute(match)
                    when (result) {
                        is WorkflowResult.Failure -> { Log.e(TAG, "✖ WORKFLOW FAILED: ${result.error}"); if (matches.size == 1) speak("Workflow failed: ${result.error}") }
                        else -> Log.d(TAG, "✓ WORKFLOW ${index + 1}/${matches.size} SUCCEEDED")
                    }
                }
            }
            resumeMusicIfNeeded()
            updateNotification("Ready - Long press power button to speak")
        } catch (e: Exception) {
            Log.e(TAG, "✖ WORKFLOW EXCEPTION: ${e.message}", e)
            resumeMusicIfNeeded()
            speak("Sorry, something went wrong.")
            updateNotification("Ready - Long press power button to speak")
        }
    }
    private fun updateGoldenDatasetWithWorkflow(transcriptId: Long, workflowName: String) {
        try {
            val transcript = transcriptRepository.getById(transcriptId)
            transcript?.audioFilePath?.takeIf { it.isNotBlank() }?.let { audioPath ->
                goldenDatasetRepository.getAll().find { it.audioFilePath == audioPath && it.transcript == transcript.text }?.let { dataset ->
                    dataset.matchedWorkflow = workflowName
                    goldenDatasetRepository.update(dataset)
                    Log.d(TAG, "📊 GOLDEN DATASET UPDATED with workflow: $workflowName")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "✖ GOLDEN DATASET UPDATE ERROR: ${e.message}")
        }
    }
    private fun handleSttError(errorCode: Int) { resumeMusicIfNeeded(); pendingQuickStartWorkflowId = null; currentAudioFilePath = null; updateNotification("Ready - Long press power button to speak") }
    private fun handleTtsError(errorCode: Int) = Unit
    fun startQuickStartRecording(workflowId: Long) { pendingQuickStartWorkflowId = workflowId; updateNotification("Quick Start: Listening..."); deepgramStt?.start() }
    fun cancelQuickStart() { pendingQuickStartWorkflowId = null; deepgramStt?.stop(); currentAudioFilePath = null; updateNotification("Ready - Long press power button to speak") }
    fun isRecording() = deepgramStt?.isRecording() == true
    fun stopRecordingManually() {
        Log.d(TAG, "⏹ MANUAL STOP (Volume Down pressed)")
        deepgramStt?.stop()
    }
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
            resumeMusicIfNeeded()
            updateNotification("Ready - Long press power button to speak")
        } catch (e: Exception) {
            Log.e(TAG, "✖ DIRECT EXECUTION EXCEPTION: ${e.message}", e)
            speak("Sorry, something went wrong.")
            resumeMusicIfNeeded()
            updateNotification("Ready - Long press power button to speak")
        }
    }
    fun triggerWakeWordManually() = startListening()
    private fun checkAndPauseMusic() {
        try {
            Log.d(TAG, "🎵 Pausing music for voice command")
            val result = musicPlayerHandler.execute("music.pause", org.json.JSONObject())
            wasMusicPlayingBeforeCommand = result.optBoolean("success", false)
        } catch (e: Exception) {
            Log.d(TAG, "No music playing or music service not available")
            wasMusicPlayingBeforeCommand = false
        }
    }
    private fun resumeMusicIfNeeded() {
        if (wasMusicPlayingBeforeCommand) {
            wasMusicPlayingBeforeCommand = false
            serviceScope.launch {
                delay(500)
                try {
                    Log.d(TAG, "🎵 Resuming music after voice command")
                    musicPlayerHandler.execute("music.resume", org.json.JSONObject())
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to resume music: ${e.message}")
                }
            }
        }
    }
}
