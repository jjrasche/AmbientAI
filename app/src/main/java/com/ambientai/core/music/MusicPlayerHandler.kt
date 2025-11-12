package com.ambientai.core.music

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusicPlayerHandler @Inject constructor(@ApplicationContext private val context: Context, private val musicScanner: MusicScanner) {
    private var serviceStarted = false
    companion object { var currentSong: Song? = null }
    fun execute(actionName: String, input: JSONObject) = when (actionName) {
        "music.play" -> {
            val query = input.optString("query", "")
            if (query.isBlank()) errorResult("Query cannot be empty")
            else {
                val matches = musicScanner.findMatches(query)
                if (matches.isEmpty()) errorResult("No songs found matching '$query'")
                else {
                    val song = matches.first()
                    currentSong = song
                    ensureServiceStarted()
                    sendCommandToService(MusicPlayerService.ACTION_PLAY, input)
                    successResult(mapOf("song" to song.title, "artist" to song.artist, "album" to song.album))
                }
            }
        }
        "music.pause" -> { ensureServiceStarted(); sendCommandToService(MusicPlayerService.ACTION_PAUSE, input); successResult(mapOf("message" to "Paused")) }
        "music.resume" -> { ensureServiceStarted(); sendCommandToService(MusicPlayerService.ACTION_PLAY, input); successResult(mapOf("message" to "Resumed")) }
        "music.stop" -> { ensureServiceStarted(); sendCommandToService(MusicPlayerService.ACTION_STOP, input); successResult(mapOf("message" to "Stopped")) }
        "music.next" -> { ensureServiceStarted(); sendCommandToService(MusicPlayerService.ACTION_NEXT, input); currentSong?.let { successResult(mapOf("song" to "Next song", "artist" to "")) } ?: errorResult("No song playing") }
        "music.previous" -> { ensureServiceStarted(); sendCommandToService(MusicPlayerService.ACTION_PREVIOUS, input); currentSong?.let { successResult(mapOf("song" to "Previous song", "artist" to "")) } ?: errorResult("No song playing") }
        "music.getNowPlaying" -> currentSong?.let { successResult(mapOf("song" to it.title, "artist" to it.artist, "album" to it.album, "response" to "${it.title} by ${it.artist}")) } ?: errorResult("Nothing is playing")
        else -> errorResult("Unknown action: $actionName")
    }
    private fun successResult(data: Map<String, Any?> = emptyMap()) = JSONObject().apply { put("success", true); data.forEach { (k, v) -> put(k, v) } }
    private fun errorResult(message: String) = JSONObject().apply { put("success", false); put("error", message) }
    private fun ensureServiceStarted() {
        if (!serviceStarted) {
            ContextCompat.startForegroundService(context, Intent(context, MusicPlayerService::class.java))
            serviceStarted = true
        }
    }
    private fun sendCommandToService(action: String, input: JSONObject): JSONObject {
        context.startService(Intent(context, MusicPlayerService::class.java).apply {
            this.action = action
            putExtra("input", input.toString())
        })
        return JSONObject().apply { put("success", true) }
    }
}
