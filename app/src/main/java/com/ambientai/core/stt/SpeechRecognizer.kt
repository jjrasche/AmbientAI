package com.ambientai.core.stt

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer as AndroidSpeechRecognizer
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlin.coroutines.coroutineContext

class SpeechRecognizer(private val context: Context, private val onPartialTranscript: (text: String) -> Unit, private val onTranscriptReady: (text: String) -> Unit, private val onRecognitionError: (errorCode: Int) -> Unit) {
    private var speechRecognizer: AndroidSpeechRecognizer? = null
    private var pauseDetectionJob: Job? = null
    private var lastResultTime = 0L
    private var currentTranscript = StringBuilder()
    private var isRecording = false

    companion object { private const val PAUSE_THRESHOLD_MS = 5000L }
    fun initialize() = (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED && AndroidSpeechRecognizer.isRecognitionAvailable(context)).also { if (it) speechRecognizer = AndroidSpeechRecognizer.createSpeechRecognizer(context).apply { setRecognitionListener(recognitionListener) } }
    fun start() { if (isRecording) return; currentTranscript.clear(); lastResultTime = System.currentTimeMillis(); Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true); putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1) }.let { speechRecognizer?.startListening(it) }; isRecording = true; startPauseDetection() }
    fun stop() { if (!isRecording) return; isRecording = false; pauseDetectionJob?.cancel(); speechRecognizer?.stopListening() }
    fun cleanup() { stop(); speechRecognizer?.destroy(); speechRecognizer = null }
    private fun startPauseDetection() { pauseDetectionJob = CoroutineScope(Dispatchers.Default).launch { while (isRecording && coroutineContext.isActive) { delay(500); (System.currentTimeMillis() - lastResultTime).let { timeSinceLastResult -> if (currentTranscript.isNotEmpty() && timeSinceLastResult > PAUSE_THRESHOLD_MS) finalizeTranscript() } } } }
    private fun finalizeTranscript() = currentTranscript.toString().trim().takeIf { it.isNotEmpty() }?.let { stop() }
    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() { lastResultTime = System.currentTimeMillis() }
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onError(error: Int) { if (isRecording) { isRecording = false; pauseDetectionJob?.cancel(); speechRecognizer?.stopListening(); onRecognitionError(error) } }
        override fun onResults(results: Bundle?) { results?.getStringArrayList(AndroidSpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { result -> currentTranscript.append(result).append(" ").toString().trim().takeIf { it.isNotEmpty() }?.let { text -> stop(); onTranscriptReady(text) } } }
        override fun onPartialResults(partialResults: Bundle?) { partialResults?.getStringArrayList(AndroidSpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { partial -> onPartialTranscript(partial); lastResultTime = System.currentTimeMillis() } }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}
