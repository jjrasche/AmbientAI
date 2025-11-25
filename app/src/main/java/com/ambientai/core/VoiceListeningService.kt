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
    private var audioFeedback: AudioFeedbackService? = null

    @Inject lateinit var transcriptRepository: ITranscriptRepository
    @Inject lateinit var goldenDatasetRepository: IGoldenDatasetRepository
    @Inject lateinit var workflowRouter: WorkflowRouter
    @Inject lateinit var workflowExecutor: WorkflowExecutor
    @Inject lateinit var musicPlayerHandler: com.ambientai.core.music.MusicPlayerHandler
    @Inject lateinit var debugServer: com.ambientai.debug.DebugServer
    @Inject lateinit var workflowSeeder: com.ambientai.data.WorkflowSeeder
    @Inject lateinit var mediaIntelligence: com.ambientai.core.media.MediaIntelligenceManager
    @Inject lateinit var ttsService: TextToSpeechService
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val binder = LocalBinder()
    private val listeners = mutableSetOf<TranscriptUpdateListener>()
    private var pendingQuickStartWorkflowId: Long? = null
    private var wasMusicPlayingBeforeCommand = false
    private var currentAudioFilePath: String? = null
    private var lastPartialTranscript = ""
    private var workflowTriggeredDuringRecording = false
    private var recordingStartTime: Long = 0

    // Streaming execution state
    private var accumulatedTranscript = StringBuilder()
    private var pauseDetectionJob: Job? = null
    private var consumedTriggerEnd = 0
    private var streamingMode = false
    private val executedWorkflowVariables = mutableMapOf<String, Any>()
    private var currentTranscriptId: Long = -1

    companion object {
        private const val TAG = "VoiceService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "ambient_ai_voice_channel"
        private const val ACTION_STOP_RECORDING = "com.ambientai.STOP_RECORDING"
        private const val PAUSE_DETECTION_MS = 1000L
        @Volatile private var isRunning = false
        @Volatile private var isTtsSpeaking = false
        @Volatile private var instance: VoiceListeningService? = null
        fun isServiceRunning() = isRunning
        fun getInstance() = instance
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
        instance = this
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
        instance = null
        audioFeedback?.cleanup()
        deepgramStt?.cleanup()
        ttsService.cleanup()
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
            if (!ttsService.initialize()) return@launch
            deepgramStt = DeepgramSttService(applicationContext, ::handlePartialTranscript, ::handleTranscript, ::handleSttError, ::handleAudioSaved, ::handleRecordingStopped)
            if (!deepgramStt!!.initialize()) { Log.e(TAG, "✖ DEEPGRAM STT INIT FAILED"); return@launch }
            deepgramStt?.onFinalChunk = { chunk, timestamp -> handleFinalChunk(chunk, timestamp) }
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
        if (isTtsSpeaking) { Log.d(TAG, "⏹ Interrupting TTS"); ttsService.stop(); isTtsSpeaking = false }
        lastPartialTranscript = ""
        workflowTriggeredDuringRecording = false
        // Reset streaming execution state
        accumulatedTranscript.clear()
        pauseDetectionJob?.cancel()
        consumedTriggerEnd = 0
        streamingMode = false
        executedWorkflowVariables.clear()
        currentTranscriptId = -1
        updateNotification("Listening...")
        audioFeedback?.playStartTone()
        serviceScope.launch { deepgramStt?.start() }
    }

    /**
     * Start processing from audio data instead of microphone.
     * Used for E2E testing to run audio through the same streaming pipeline.
     * Returns a Deferred that completes when all processing is done.
     */
    fun startFromAudioData(audioData: ByteArray): Deferred<String?> {
        val result = CompletableDeferred<String?>()
        recordingStartTime = System.currentTimeMillis()
        Log.d(TAG, "🎤 TEST RECORDING STARTED at ${recordingStartTime} (${audioData.size} bytes)")

        // Reset streaming execution state (same as startListening)
        lastPartialTranscript = ""
        workflowTriggeredDuringRecording = false
        accumulatedTranscript.clear()
        pauseDetectionJob?.cancel()
        consumedTriggerEnd = 0
        streamingMode = false
        executedWorkflowVariables.clear()
        currentTranscriptId = -1

        // Store completion callback
        testCompletionDeferred = result

        // Start audio processing through Deepgram
        serviceScope.launch { deepgramStt?.startFromAudioData(audioData) }

        return result
    }

    private var testCompletionDeferred: CompletableDeferred<String?>? = null

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
        // Just log potential matches for debugging - don't execute until we have the full transcript
        // This prevents cutting off transcripts like "start task regression testing" → "start task"
        if (elapsedMs < 600 || wordCount > 4) return@launch
        try {
            val matches = workflowRouter.route(partialText, -1, isPartial = true)
            val match = matches.firstOrNull()
            if (match != null) {
                Log.d(TAG, "🔎 POTENTIAL MATCH [${elapsedMs}ms]: ${match.definition.name} from \"$partialText\" (waiting for final)")
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
        resumeMusicIfNeeded()
        updateNotification("Ready - Long press power button to speak")
    }
    private fun handleAudioSaved(filePath: String) {
        Log.d(TAG, "💾 AUDIO SAVED CALLBACK: $filePath")
        currentAudioFilePath = filePath
    }
    private fun handleRecordingStopped() = serviceScope.launch {
        Log.d(TAG, "🔔 RECORDING STOPPED")
        audioFeedback?.playStopTone()
        // Cancel any pending pause detection - final transcript handler will process remaining
        pauseDetectionJob?.cancel()
    }
    private fun handleFinalChunk(chunk: String, timestamp: Long) {
        if (chunk.isBlank()) return
        val elapsed = System.currentTimeMillis() - recordingStartTime
        Log.d(TAG, "⚡ STREAMING FINAL [${elapsed}ms]: \"$chunk\"")
        // Accumulate the chunk
        if (accumulatedTranscript.isNotEmpty()) accumulatedTranscript.append(" ")
        accumulatedTranscript.append(chunk)
        streamingMode = true
        // Cancel previous pause detection and start new one
        pauseDetectionJob?.cancel()
        pauseDetectionJob = serviceScope.launch {
            delay(PAUSE_DETECTION_MS)
            onPauseDetected()
        }
    }
    private fun onPauseDetected() = serviceScope.launch {
        val fullText = accumulatedTranscript.toString().trim()
        if (fullText.isEmpty()) return@launch
        // Get unconsumed portion of transcript
        val unconsumedText = if (consumedTriggerEnd < fullText.length) {
            fullText.substring(consumedTriggerEnd).trim()
        } else {
            ""
        }
        if (unconsumedText.isEmpty()) {
            Log.d(TAG, "🔄 PAUSE DETECTED but no new text to process")
            return@launch
        }
        Log.d(TAG, "🔄 PAUSE DETECTED - processing: \"$unconsumedText\"")
        // Save transcript if not yet saved
        if (currentTranscriptId == -1L) {
            val transcript = Transcript(text = fullText, audioFilePath = currentAudioFilePath ?: "", timestamp = System.currentTimeMillis(), excludeFromContext = false)
            val saved = transcriptRepository.save(transcript)
            currentTranscriptId = saved.id
            Log.d(TAG, "💾 SAVED TRANSCRIPT: id=$currentTranscriptId")
        }
        // Route the unconsumed text
        val matches = workflowRouter.route(unconsumedText, currentTranscriptId)
        if (matches.isEmpty()) {
            Log.d(TAG, "🔄 No workflow match for: \"$unconsumedText\"")
            return@launch
        }
        Log.d(TAG, "⚡ STREAMING EXECUTE ${matches.size} workflow(s): ${matches.map { it.definition.name }}")
        // Execute each matched workflow
        matches.forEach { match ->
            // Check conditions at execution time
            if (!workflowRouter.checkConditions(match.definition)) {
                Log.d(TAG, "⏭ SKIPPING ${match.definition.name}: conditions not met")
                return@forEach
            }
            // Propagate variables from previous executions
            executedWorkflowVariables.forEach { (key, value) ->
                match.context.variables[key] = value
            }
            listeners.forEach { it.onWorkflowStarted(match.definition.name, unconsumedText) }
            val result = workflowExecutor.execute(match)
            when (result) {
                is WorkflowResult.Success -> {
                    Log.d(TAG, "✓ STREAMING WORKFLOW ${match.definition.name} SUCCEEDED")
                    // Propagate output variables for next workflow
                    result.variables.forEach { (key, value) ->
                        executedWorkflowVariables[key] = value
                    }
                }
                is WorkflowResult.Failure -> {
                    Log.e(TAG, "✖ STREAMING WORKFLOW ${match.definition.name} FAILED: ${result.error}")
                }
            }
            listeners.forEach { it.onWorkflowCompleted(match.definition.name) }
            // Update consumed trigger position based on match position
            val triggerEndInFull = consumedTriggerEnd + match.triggerEnd
            if (triggerEndInFull > consumedTriggerEnd) {
                consumedTriggerEnd = triggerEndInFull
                Log.d(TAG, "📍 Consumed trigger end updated to: $consumedTriggerEnd")
            }
        }
        workflowTriggeredDuringRecording = true
    }
    private fun handleTranscript(text: String) {
        if (workflowTriggeredDuringRecording) {
            Log.d(TAG, "📝 FINAL TRANSCRIPT (streaming execution already handled): \"$text\"")
            workflowTriggeredDuringRecording = false
            // Update transcript with full text if it was saved during streaming
            if (currentTranscriptId > 0) {
                transcriptRepository.getById(currentTranscriptId)?.let { existing ->
                    existing.text = text
                    transcriptRepository.save(existing)
                    listeners.forEach { it.onTranscriptSaved(existing) }
                }
            }
            resumeMusicIfNeeded()
            updateNotification("Ready - Long press power button to speak")
            testCompletionDeferred?.complete(text)
            testCompletionDeferred = null
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
        val wasRecording = !headphonesConnected && deepgramStt?.isRecording() == true
        if (wasRecording) {
            Log.d(TAG, "🔇 Pausing STT during TTS (no headphones)")
            deepgramStt?.stop()
        }
        ttsService.speak(text)
        if (wasRecording && !ttsService.testMode) {
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
                var executedCount = 0
                matches.forEach { match ->
                    // Check conditions at execution time (allows sequential workflows to affect state)
                    if (!workflowRouter.checkConditions(match.definition)) {
                        Log.d(TAG, "⏭ SKIPPING ${match.definition.name}: conditions not met")
                        return@forEach
                    }
                    if (executedCount == 0) {
                        listeners.forEach { it.onWorkflowStarted(match.definition.name, text) }
                        updateGoldenDatasetWithWorkflow(transcriptId, match.definition.name)
                    }
                    val result = workflowExecutor.execute(match)
                    executedCount++
                    when (result) {
                        is WorkflowResult.Failure -> { Log.e(TAG, "✖ WORKFLOW FAILED: ${result.error}"); if (matches.size == 1) speak("Workflow failed: ${result.error}") }
                        else -> Log.d(TAG, "✓ WORKFLOW $executedCount SUCCEEDED")
                    }
                }
                if (executedCount == 0) {
                    Log.w(TAG, "⚠ All workflows skipped due to conditions")
                    speak("No workflow matched.")
                }
            }
            resumeMusicIfNeeded()
            updateNotification("Ready - Long press power button to speak")
            testCompletionDeferred?.complete(text)
            testCompletionDeferred = null
        } catch (e: Exception) {
            Log.e(TAG, "✖ WORKFLOW EXCEPTION: ${e.message}", e)
            resumeMusicIfNeeded()
            speak("Sorry, something went wrong.")
            updateNotification("Ready - Long press power button to speak")
            testCompletionDeferred?.complete(null)
            testCompletionDeferred = null
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
