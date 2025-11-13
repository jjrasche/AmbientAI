package com.ambientai.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Log
import kotlinx.coroutines.*
import kotlin.math.PI
import kotlin.math.sin

class AudioFeedbackService(private val context: Context) {
    companion object {
        private const val TAG = "AudioFeedback"
        private const val SAMPLE_RATE = 44100
    }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    fun playStartTone() {
        scope.launch {
            try {
                Log.d(TAG, "🔔 Playing START tone")
                playTone(800f, 150, ascending = true)
            } catch (e: Exception) {
                Log.e(TAG, "Error playing start tone", e)
            }
        }
    }
    fun playStopTone() {
        scope.launch {
            try {
                Log.d(TAG, "🔕 Playing STOP tone")
                playTone(400f, 150, ascending = false)
            } catch (e: Exception) {
                Log.e(TAG, "Error playing stop tone", e)
            }
        }
    }
    private fun playTone(startFreq: Float, durationMs: Int, ascending: Boolean) {
        val numSamples = (SAMPLE_RATE * durationMs) / 1000
        val buffer = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val progress = i.toFloat() / numSamples
            val freq = if (ascending) startFreq + (progress * 200f) else startFreq - (progress * 200f)
            val amplitude = 0.3f * (1.0f - progress * 0.5f)
            buffer[i] = (amplitude * 32767 * sin(2.0 * PI * freq * i / SAMPLE_RATE)).toInt().toShort()
        }
        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(buffer.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        audioTrack.write(buffer, 0, buffer.size)
        audioTrack.play()
        Thread.sleep((buffer.size * 1000L) / SAMPLE_RATE)
        audioTrack.stop()
        audioTrack.release()
    }
    fun cleanup() = scope.cancel()
}
