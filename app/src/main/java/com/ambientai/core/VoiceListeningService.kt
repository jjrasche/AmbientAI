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
import com.ambientai.data.entities.Transcript
import com.ambientai.data.repositories.TranscriptRepository
import kotlinx.coroutines.*

class VoiceListeningService : Service() {

    private var wakeWordDetector: WakeWordDetector? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var transcriptRepository: TranscriptRepository? = null
    private var llmService: GroqLlmService? = null
    private var ttsService: TextToSpeechService? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val binder = LocalBinder()
    private val listeners = mutableSetOf<TranscriptUpdateListener>()

    companion object {
        private const val TAG = "VoiceListeningService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "ambient_ai_voice_channel"

        // Conversational triggers
        private val ANSWER_TRIGGERS = listOf(
            "answer me",
            "what do you think",
            "your thoughts",
            "can you help",
            "tell me"
        )
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
        llmService = GroqLlmService()

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
        transcriptRepository?.close()
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

                Log.d(TAG, "All components initialized")
                wakeWordDetector?.start()
                updateNotification("Listening for wake word...")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize components", e)
            }
        }
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

        val transcript = Transcript(
            text = text,
            audioFilePath = "",
            timestamp = System.currentTimeMillis()
        )
        transcriptRepository?.save(transcript)

        listeners.forEach { it.onTranscriptSaved(transcript) }

        // Check if this triggers a conversational response
        if (shouldRespond(text)) {
            handleConversationalQuery()
        } else {
            updateNotification("Listening for wake word...")
            wakeWordDetector?.start()
        }
    }

    private fun shouldRespond(text: String): Boolean {
        val lowerText = text.lowercase()
        return ANSWER_TRIGGERS.any { trigger -> lowerText.contains(trigger) }
    }

    private fun handleConversationalQuery() {
        serviceScope.launch {
            try {
                updateNotification("Thinking...")

                // Get recent context (last 3 transcripts) with timestamps
                val context = transcriptRepository?.getRecentContext(3) ?: ""

                if (context.isEmpty()) {
                    ttsService?.speak("I don't have any recent context.")
                    updateNotification("Listening for wake word...")
                    wakeWordDetector?.start()
                    return@launch
                }

                // Generate response
                val systemPrompt = "You are a helpful assistant. Provide conversational responses in 1-2 sentences."
                val result = llmService?.generateResponse(
                    systemPrompt = systemPrompt,
                    userPrompt = context,
                    temperature = 0.7f,
                    maxTokens = 100
                )

                result?.onSuccess { response ->
                    Log.d(TAG, "LLM response: $response")

                    // Speak the response
                    updateNotification("Speaking...")
                    ttsService?.speak(response)

                    // Save LLM response as a transcript for context
                    val assistantTranscript = Transcript(
                        text = "[Assistant] $response",
                        audioFilePath = "",
                        timestamp = System.currentTimeMillis()
                    )
                    transcriptRepository?.save(assistantTranscript)
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