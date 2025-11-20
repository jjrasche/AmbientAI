package com.ambientai.testing

import android.content.Context
import android.util.Log
import com.ambientai.core.stt.DeepgramSttService
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TestRecordingManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "TestRecording"
        private const val RECORDINGS_DIR = "AmbientAI/test_recordings"
    }
    private var currentRecording: RecordingSession? = null
    private var deepgramStt: DeepgramSttService? = null
    data class RecordingSession(
        val testId: String,
        val startTime: Long,
        var partialTranscripts: MutableList<String> = mutableListOf(),
        var finalTranscript: String? = null,
        var audioFilePath: String? = null,
        var completed: Boolean = false,
        var error: String? = null
    )
    data class RecordingResult(
        val testId: String,
        val transcript: String,
        val audioFilePath: String,
        val partialTranscripts: List<String>,
        val durationMs: Long
    )
    fun isRecording() = currentRecording != null && !currentRecording!!.completed
    fun getCurrentSession() = currentRecording
    fun startRecording(testId: String): Boolean {
        if (isRecording()) {
            Log.w(TAG, "⚠ Recording already in progress for ${currentRecording?.testId}")
            return false
        }
        Log.d(TAG, "🎤 STARTING RECORDING for test: $testId")
        val session = RecordingSession(testId = testId, startTime = System.currentTimeMillis())
        currentRecording = session
        deepgramStt = DeepgramSttService(
            context = context,
            onPartialTranscript = { text -> handlePartialTranscript(text) },
            onTranscriptReady = { text -> handleFinalTranscript(text) },
            onRecognitionError = { errorCode -> handleError("STT error code: $errorCode") },
            audioSaveCallback = { filePath -> handleAudioSaved(filePath) },
            onRecordingStopped = { handleRecordingStopped() }
        )
        if (!deepgramStt!!.initialize()) {
            session.error = "Failed to initialize Deepgram STT"
            session.completed = true
            Log.e(TAG, "✖ STT INIT FAILED")
            return false
        }
        deepgramStt!!.start()
        return true
    }
    fun stopRecording(): RecordingSession? {
        if (!isRecording()) {
            Log.w(TAG, "⚠ No active recording to stop")
            return null
        }
        Log.d(TAG, "⏹ STOPPING RECORDING")
        deepgramStt?.stop()
        return currentRecording
    }
    private fun handlePartialTranscript(text: String) {
        currentRecording?.let { session ->
            val elapsed = System.currentTimeMillis() - session.startTime
            Log.d(TAG, "📝 PARTIAL [${elapsed}ms]: \"$text\"")
            session.partialTranscripts.add(text)
        }
    }
    private fun handleFinalTranscript(text: String) {
        currentRecording?.let { session ->
            val elapsed = System.currentTimeMillis() - session.startTime
            Log.d(TAG, "✅ FINAL TRANSCRIPT [${elapsed}ms]: \"$text\"")
            session.finalTranscript = text
        }
    }
    private fun handleAudioSaved(filePath: String) {
        currentRecording?.let { session ->
            Log.d(TAG, "💾 AUDIO SAVED: $filePath")
            val destFile = moveToExternalStorage(filePath, session.testId)
            session.audioFilePath = destFile.absolutePath
        }
    }
    private fun handleRecordingStopped() {
        currentRecording?.let { session ->
            val elapsed = System.currentTimeMillis() - session.startTime
            Log.d(TAG, "🏁 RECORDING COMPLETE [${elapsed}ms]")
            session.completed = true
        }
    }
    private fun handleError(error: String) {
        currentRecording?.let { session ->
            Log.e(TAG, "✖ RECORDING ERROR: $error")
            session.error = error
            session.completed = true
        }
    }
    private fun moveToExternalStorage(sourcePath: String, testId: String): File {
        val sourceFile = File(sourcePath)
        val externalDir = File(context.getExternalFilesDir(null), "test_recordings")
        externalDir.mkdirs()
        val destFile = File(externalDir, "${testId}.wav")
        if (sourceFile.exists()) {
            sourceFile.copyTo(destFile, overwrite = true)
            Log.d(TAG, "📁 Copied to: ${destFile.absolutePath}")
            Log.d(TAG, "📱 Pull via: adb pull ${destFile.absolutePath} app/src/main/assets/test_audio/")
        }
        return destFile
    }
    fun getRecordingFile(testId: String): File {
        val externalDir = File(context.getExternalFilesDir(null), "test_recordings")
        return File(externalDir, "${testId}.wav")
    }
    fun cleanup() {
        deepgramStt?.cleanup()
        deepgramStt = null
        currentRecording = null
    }
}
