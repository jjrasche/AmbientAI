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
import com.ambientai.core.llm.GroqLlmService
import com.ambientai.core.stt.SpeechRecognizer
import com.ambientai.core.tts.TextToSpeechService
import com.ambientai.core.wake.WakeWordDetector
import com.ambientai.data.entities.LlmInteraction
import com.ambientai.data.entities.Transcript
import com.ambientai.data.repositories.LlmInteractionRepository
import com.ambientai.data.repositories.TranscriptRepository
import com.ambientai.workflow.MultipleMatchException
import com.ambientai.workflow.WorkflowExecutor
import com.ambientai.workflow.WorkflowRouter
import kotlinx.coroutines.*

class VoiceListeningService : Service() {

    private var wakeWordDetector: WakeWordDetector? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var transcriptRepository: TranscriptRepository? = null
    private var llmInteractionRepository: LlmInteractionRepository? = null
    private var llmService: GroqLlmService? = null
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

        // Legacy hardcoded triggers - kept for backward compatibility
        // TODO: Remove once all workflows are migrated to WorkflowRouter
        private val NOTE_TRIGGERS = listOf(
            "note this",
            "just noting",
            "take a note"
        )

        private val CLEAR_CONTEXT_TRIGGERS = listOf(
            "clear context",
            "reset context",
            "new context"
        )

        private val GRADE_PATTERN = Regex("""grade\s+that\s+(\d)""", RegexOption.IGNORE_CASE)
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
        llmInteractionRepository = LlmInteractionRepository(applicationContext)
        llmService = GroqLlmService()
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
                // Initialize TTS first
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

                // Load workflows into router
                workflowRouter?.loadWorkflows()
                Log.d(TAG, "Workflows loaded into router")

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

    private fun shouldNote(text: String): Boolean {
        val lowerText = text.lowercase()
        return NOTE_TRIGGERS.any { trigger -> lowerText.contains(trigger) }
    }

    private fun handleNote() {
        serviceScope.launch {
            ttsService?.speak("Noted.")
            updateNotification("Listening for wake word...")
            wakeWordDetector?.start()
        }
    }

    private fun handleTranscript(text: String) {
        Log.d(TAG, "Transcript received: $text")

        val transcript = Transcript(
            text = text,
            audioFilePath = "",
            timestamp = System.currentTimeMillis(),
            excludeFromContext = false
        )
        transcriptRepository?.save(transcript)

        listeners.forEach { it.onTranscriptSaved(transcript) }

        // Check for legacy hardcoded triggers first
        // TODO: Migrate these to WorkflowDefinitions and remove
        when {
            shouldClearContext(text) -> handleClearContext()
            shouldGrade(text) -> handleGrade(text)
            shouldNote(text) -> handleNote()
            else -> routeToWorkflow(text)
        }
    }

    /**
     * Route transcript to workflow using WorkflowRouter.
     * Handles MultipleMatchException by speaking error to user.
     */
    private fun routeToWorkflow(text: String) {
        serviceScope.launch {
            try {
                val match = workflowRouter?.route(text)

                if (match == null) {
                    // No workflow matched - use fallback conversational response
                    Log.d(TAG, "No workflow matched, using conversational fallback")
                    handleConversationalQuery()
                } else {
                    // Workflow matched - execute it
                    Log.d(TAG, "Routing to workflow: ${match.definition.name}")
                    // TODO: Pass to WorkflowExecutor (Phase 3)
                    // For now, fallback to conversational response
                    handleConversationalQuery()
                }

            } catch (e: MultipleMatchException) {
                // Multiple workflows matched - speak error
                Log.w(TAG, "Multiple workflows matched: ${e.matchedWorkflows}")
                val workflowList = e.matchedWorkflows.joinToString(", ")
                ttsService?.speak("Multiple workflows matched: $workflowList. Please be more specific.")

                updateNotification("Listening for wake word...")
                wakeWordDetector?.start()
            } catch (e: Exception) {
                Log.e(TAG, "Error routing to workflow", e)
                ttsService?.speak("Sorry, something went wrong.")

                updateNotification("Listening for wake word...")
                wakeWordDetector?.start()
            }
        }
    }

    private fun shouldClearContext(text: String): Boolean {
        val lowerText = text.lowercase()
        return CLEAR_CONTEXT_TRIGGERS.any { trigger -> lowerText.contains(trigger) }
    }

    private fun shouldGrade(text: String): Boolean {
        return GRADE_PATTERN.containsMatchIn(text)
    }

    private fun handleClearContext() {
        serviceScope.launch {
            try {
                transcriptRepository?.clearContext()
                Log.d(TAG, "Context cleared")
                ttsService?.speak("Context cleared.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear context", e)
                ttsService?.speak("Failed to clear context.")
            }

            updateNotification("Listening for wake word...")
            wakeWordDetector?.start()
        }
    }

    private fun handleGrade(text: String) {
        serviceScope.launch {
            try {
                val match = GRADE_PATTERN.find(text)
                val gradeStr = match?.groupValues?.get(1)
                val grade = gradeStr?.toIntOrNull()

                if (grade == null || grade !in 0..5) {
                    Log.w(TAG, "Invalid grade: $gradeStr")
                    ttsService?.speak("Grade must be between 0 and 5.")
                    updateNotification("Listening for wake word...")
                    wakeWordDetector?.start()
                    return@launch
                }

                val mostRecent = llmInteractionRepository?.getMostRecent()
                if (mostRecent == null) {
                    Log.w(TAG, "No LLM interaction to grade")
                    ttsService?.speak("No response to grade.")
                } else {
                    llmInteractionRepository?.updateGrade(mostRecent.id, grade)
                    Log.d(TAG, "Graded interaction ${mostRecent.id} as $grade")
                    ttsService?.speak("Graded $grade out of 5.")
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to grade response", e)
                ttsService?.speak("Failed to grade response.")
            }

            updateNotification("Listening for wake word...")
            wakeWordDetector?.start()
        }
    }

    private fun handleConversationalQuery() {
        serviceScope.launch {
            try {
                updateNotification("Thinking...")

                // Get recent context (last 3 transcripts) with timestamps
                val userPrompt = transcriptRepository?.getRecentContext(3) ?: ""

                if (userPrompt.isEmpty()) {
                    ttsService?.speak("I don't have any recent context.")
                    updateNotification("Listening for wake word...")
                    wakeWordDetector?.start()
                    return@launch
                }

                // Generate response
                val systemPrompt = "You are a helpful assistant. Provide conversational responses in 1-2 sentences."
                val temperature = 0.7f
                val maxTokens = 100
                val startTime = System.currentTimeMillis()

                val result = llmService?.generateResponse(
                    systemPrompt = systemPrompt,
                    userPrompt = userPrompt,
                    temperature = temperature,
                    maxTokens = maxTokens
                )

                result?.onSuccess { response ->
                    val latency = System.currentTimeMillis() - startTime
                    Log.d(TAG, "LLM response: $response")

                    // Save complete LLM interaction to database
                    val interaction = LlmInteraction(
                        systemPrompt = systemPrompt,
                        userPrompt = userPrompt,
                        response = response,
                        timestamp = System.currentTimeMillis(),
                        latencyMs = latency,
                        model = "llama-3.1-8b-instant",
                        temperature = temperature,
                        maxTokens = maxTokens,
                        grade = null
                    )
                    llmInteractionRepository?.save(interaction)

                    // Speak the response
                    updateNotification("Speaking...")
                    ttsService?.speak(response)

                }?.onFailure { error ->
                    Log.e(TAG, "LLM failed", error)
                    ttsService?.speak("Sorry, I couldn't process that.")
                }

                // Resume wake word detection
                updateNotification("Listening for wake word...")
                wakeWordDetector?.start()

            } catch (e: Exception) {
                Log.e(TAG, "Conversational query failed", e)
                ttsService?.speak("Sorry, something went wrong.")
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