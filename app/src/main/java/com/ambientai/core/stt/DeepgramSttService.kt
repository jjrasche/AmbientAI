package com.ambientai.core.stt

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import com.ambientai.BuildConfig
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DeepgramSttService(private val context: Context, private val onPartialTranscript: (text: String) -> Unit, private val onTranscriptReady: (text: String) -> Unit, private val onRecognitionError: (errorCode: Int) -> Unit, private val audioSaveCallback: (filePath: String) -> Unit, private val onRecordingStopped: () -> Unit) {
    private var webSocket: WebSocket? = null
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private var recordingJob: Job? = null
    private var vadTimeoutJob: Job? = null
    private var currentTranscript = StringBuilder()
    private var lastPartialTranscript = ""
    private var lastSpeechTime = 0L
    private var audioBuffer = mutableListOf<ByteArray>()
    private var audioFilePath: String? = null
    private var recordingStartTime = 0L

    // Streaming execution support
    var onFinalChunk: ((chunk: String, timestamp: Long) -> Unit)? = null
    @Volatile private var currentAmplitude = 0
    private var webSocketReady: CompletableDeferred<Boolean>? = null
    private var transcriptComplete: CompletableDeferred<Unit>? = null

    fun getCurrentAmplitude(): Int = currentAmplitude

    companion object {
        private const val TAG = "DeepgramSTT"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val VAD_TIMEOUT_MS = 8000L
        private const val DEEPGRAM_URL = "wss://api.deepgram.com/v1/listen"
        const val ERROR_PERMISSION = 9
        const val ERROR_AUDIO_RECORD = 10
        const val ERROR_WEBSOCKET = 11
    }
    fun initialize() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    fun isRecording() = isRecording
    fun start() {
        if (isRecording) { Log.w(TAG, "⚠ STT START IGNORED (already recording)"); return }
        recordingStartTime = System.currentTimeMillis()
        Log.d(TAG, "▶ DEEPGRAM STT STARTED at $recordingStartTime")
        currentTranscript.clear()
        lastPartialTranscript = ""
        audioBuffer.clear()
        lastSpeechTime = System.currentTimeMillis()
        audioFilePath = File(context.filesDir, "audio_${System.currentTimeMillis()}.wav").absolutePath
        isRecording = true
        startWebSocket()
        startAudioCapture()
        startVadTimeout()
    }
    /**
     * Start transcription from audio file data instead of microphone.
     * Uses exact same production code path - just different audio source.
     */
    fun startFromAudioData(audioData: ByteArray) {
        if (isRecording) {
            Log.w(TAG, "⚠ STT already recording, stopping silently for test audio reset")
            // Stop without triggering callbacks - this is a test reset, not a normal stop
            stopSilently()
        }
        recordingStartTime = System.currentTimeMillis()
        // Skip WAV header (44 bytes) to get raw PCM data
        val pcmData = if (audioData.size > 44) audioData.copyOfRange(44, audioData.size) else audioData
        Log.d(TAG, "▶ DEEPGRAM STT STARTED (from audio data: ${pcmData.size} bytes)")
        currentTranscript.clear()
        lastPartialTranscript = ""
        audioBuffer.clear()
        lastSpeechTime = System.currentTimeMillis()
        audioFilePath = null // No file to save for test audio
        isRecording = true
        transcriptComplete = CompletableDeferred()
        startWebSocket()
        startAudioFromData(pcmData)
        // No VAD timeout for file playback - we wait for speech_final
    }

    /**
     * Stop recording without triggering callbacks.
     * Used for test resets where we don't want the previous test's transcript to pollute the next test.
     */
    private fun stopSilently() {
        Log.d(TAG, "■ DEEPGRAM STOPPED (silent reset)")
        isRecording = false
        webSocketReady = null
        transcriptComplete = null
        vadTimeoutJob?.cancel()
        recordingJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        webSocket?.close(1000, "Silent reset")
        webSocket = null
        // Do NOT call onTranscriptReady or onRecordingStopped - this is a reset
        currentTranscript.clear()
        lastPartialTranscript = ""
        audioBuffer.clear()
    }
    private fun startAudioFromData(pcmData: ByteArray) {
        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            // Wait for WebSocket to connect
            val connected = try {
                withTimeout(5000) { webSocketReady?.await() ?: false }
            } catch (e: Exception) {
                Log.e(TAG, "✖ WebSocket connection timeout")
                false
            }
            if (!connected) {
                stop()
                return@launch
            }
            Log.d(TAG, "🔌 WebSocket ready, sending audio...")
            val chunkSize = 3200 // 100ms of 16kHz 16-bit mono audio
            var offset = 0
            while (offset < pcmData.size && isRecording && isActive) {
                val end = minOf(offset + chunkSize, pcmData.size)
                val chunk = pcmData.copyOfRange(offset, end)
                audioBuffer.add(chunk)
                webSocket?.send(okio.ByteString.of(*chunk))
                offset = end
                delay(100) // Real-time streaming: 100ms chunk = 100ms delay
            }
            // Audio complete - give Deepgram time to process final chunk
            Log.d(TAG, "✓ Audio sending complete (${pcmData.size} bytes), waiting for speech_final...")
            // Wait for speech_final from Deepgram (with timeout)
            val deferred = transcriptComplete
            if (deferred != null) {
                try {
                    withTimeout(10000) { deferred.await() }
                } catch (e: Exception) {
                    Log.w(TAG, "⚠ Timeout waiting for speech_final, stopping anyway")
                }
            }
            stop()
        }
    }
    fun stop() {
        if (!isRecording) return
        val elapsed = System.currentTimeMillis() - recordingStartTime
        val text = currentTranscript.toString().trim()
        Log.d(TAG, "■ DEEPGRAM STOPPED (manual) [${elapsed}ms]")
        Log.d(TAG, "   ↳ Transcript buffer: \"$text\"")
        isRecording = false
        webSocketReady = null
        transcriptComplete = null
        vadTimeoutJob?.cancel()
        recordingJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        webSocket?.close(1000, "Recording stopped")
        webSocket = null
        saveAudioFile()
        onRecordingStopped()
        // Always call onTranscriptReady - let caller decide how to handle empty
        if (text.isNotEmpty()) {
            Log.d(TAG, "✓ SENDING FINAL to VoiceService: \"$text\"")
        } else {
            Log.d(TAG, "⚠ EMPTY TRANSCRIPT (no speech detected)")
        }
        onTranscriptReady(text)
    }
    fun cleanup() = stop()
    private fun startWebSocket() {
        val apiKey = BuildConfig.DEEPGRAM_API_KEY
        if (apiKey.isBlank()) { Log.e(TAG, "✖ DEEPGRAM API KEY NOT SET"); onRecognitionError(ERROR_WEBSOCKET); return }
        val request = Request.Builder().url("$DEEPGRAM_URL?model=nova-2-conversationalai&encoding=linear16&sample_rate=$SAMPLE_RATE&channels=1&interim_results=true&vad_events=true&smart_format=true").addHeader("Authorization", "Token $apiKey").build()
        webSocketReady = CompletableDeferred()
        webSocket = OkHttpClient().newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) { Log.d(TAG, "🔌 WebSocket CONNECTED"); webSocketReady?.complete(true) }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    Log.d(TAG, "📥 RAW DEEPGRAM MSG: ${text.take(500)}")
                    when (json.optString("type")) {
                        "Results" -> {
                            val channel = json.optJSONObject("channel")
                            val alternatives = channel?.optJSONArray("alternatives")
                            val transcript = alternatives?.optJSONObject(0)?.optString("transcript") ?: ""
                            val isFinal = json.optBoolean("is_final", false)
                            val speechFinal = json.optBoolean("speech_final", false)
                            val elapsed = System.currentTimeMillis() - recordingStartTime
                            Log.d(TAG, "   ↳ is_final=$isFinal, speech_final=$speechFinal")
                            // Complete transcript when we get speech_final (end of utterance)
                            if (speechFinal) {
                                transcriptComplete?.complete(Unit)
                            }
                            if (transcript.isNotBlank()) {
                                lastSpeechTime = System.currentTimeMillis()
                                val fullText = currentTranscript.toString() + transcript
                                val wordCount = fullText.split("\\s+".toRegex()).size
                                if (isFinal) {
                                    currentTranscript.append(transcript).append(" ")
                                    lastPartialTranscript = ""
                                    Log.d(TAG, "✅ DEEPGRAM FINAL CHUNK [${elapsed}ms, $wordCount words total]: \"$transcript\"")
                                    Log.d(TAG, "   ↳ Current full transcript: \"${currentTranscript.toString().trim()}\"")
                                    // Emit for streaming execution
                                    onFinalChunk?.invoke(transcript, System.currentTimeMillis())
                                } else {
                                    lastPartialTranscript = transcript
                                    Log.d(TAG, "⏳ DEEPGRAM PARTIAL [${elapsed}ms, $wordCount words]: \"$transcript\"")
                                    onPartialTranscript(fullText)
                                }
                            } else {
                                Log.d(TAG, "⚠ DEEPGRAM EMPTY RESULT [${elapsed}ms, isFinal=$isFinal]")
                            }
                        }
                        "SpeechStarted" -> {
                            val elapsed = System.currentTimeMillis() - recordingStartTime
                            Log.d(TAG, "🗣 DEEPGRAM SPEECH STARTED [${elapsed}ms]")
                            lastSpeechTime = System.currentTimeMillis()
                        }
                        "UtteranceEnd" -> {
                            val elapsed = System.currentTimeMillis() - recordingStartTime
                            Log.d(TAG, "🔇 DEEPGRAM UTTERANCE END [${elapsed}ms] (ignored - user controls stop)")
                        }
                    }
                } catch (e: Exception) { Log.e(TAG, "✖ JSON PARSE ERROR: ${e.message}") }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { Log.e(TAG, "✖ WebSocket ERROR: ${t.message}"); webSocketReady?.complete(false); isRecording = false; onRecognitionError(ERROR_WEBSOCKET) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { Log.d(TAG, "🔌 WebSocket CLOSED: $reason") }
        })
    }
    @SuppressLint("MissingPermission")
    private fun startAudioCapture() {
        val bufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
        audioRecord = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT, bufferSize)
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) { Log.e(TAG, "✖ AUDIO RECORD INIT FAILED"); onRecognitionError(ERROR_AUDIO_RECORD); return }
        audioRecord?.startRecording()
        recordingJob = CoroutineScope(Dispatchers.IO).launch {
            val buffer = ByteArray(bufferSize)
            while (isRecording && isActive) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    val audioData = buffer.copyOf(read)
                    audioBuffer.add(audioData)
                    webSocket?.send(okio.ByteString.of(*audioData))
                    // Update amplitude for barge-in detection
                    var maxAmplitude = 0
                    for (i in 0 until read step 2) {
                        if (i + 1 < read) {
                            val sample = (buffer[i].toInt() and 0xFF) or (buffer[i + 1].toInt() shl 8)
                            val amplitude = kotlin.math.abs(sample.toShort().toInt())
                            if (amplitude > maxAmplitude) maxAmplitude = amplitude
                        }
                    }
                    currentAmplitude = maxAmplitude
                }
            }
        }
    }
    private fun startVadTimeout() {
        vadTimeoutJob = CoroutineScope(Dispatchers.Default).launch {
            while (isRecording && isActive) {
                delay(500)
                val timeSinceLastSpeech = System.currentTimeMillis() - lastSpeechTime
                if (timeSinceLastSpeech > VAD_TIMEOUT_MS) {
                    Log.d(TAG, "⏱ VAD TIMEOUT (${VAD_TIMEOUT_MS}ms silence)")
                    finalizeTranscript()
                }
            }
        }
    }
    private fun finalizeTranscript() {
        if (!isRecording) return
        val finalText = currentTranscript.toString().trim()
        val text = if (finalText.isEmpty()) lastPartialTranscript else finalText
        Log.d(TAG, "🏁 FINALIZING TRANSCRIPT (VAD timeout)")
        Log.d(TAG, "   ↳ currentTranscript buffer: \"$finalText\"")
        Log.d(TAG, "   ↳ lastPartialTranscript: \"$lastPartialTranscript\"")
        Log.d(TAG, "   ↳ Using: \"$text\"")
        // stop() will call onTranscriptReady() - don't duplicate here
        stop()
    }
    private fun saveAudioFile() {
        audioFilePath?.let { path ->
            try {
                val file = File(path)
                FileOutputStream(file).use { fos ->
                    val totalAudioLen = audioBuffer.sumOf { it.size }
                    val totalDataLen = totalAudioLen + 36
                    val channels = 1
                    val byteRate = SAMPLE_RATE * channels * 2
                    fos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x46464952).array())
                    fos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(totalDataLen).array())
                    fos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x45564157).array())
                    fos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x20746d66).array())
                    fos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(16).array())
                    fos.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(1).array())
                    fos.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(channels.toShort()).array())
                    fos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(SAMPLE_RATE).array())
                    fos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(byteRate).array())
                    fos.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort((channels * 2).toShort()).array())
                    fos.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(16).array())
                    fos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(0x61746164).array())
                    fos.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(totalAudioLen).array())
                    audioBuffer.forEach { fos.write(it) }
                }
                Log.d(TAG, "💾 AUDIO SAVED: $path")
                audioSaveCallback(path)
            } catch (e: Exception) { Log.e(TAG, "✖ AUDIO SAVE ERROR: ${e.message}") }
        }
    }
}
