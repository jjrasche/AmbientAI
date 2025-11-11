package com.ambientai.core.music

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.ambientai.data.entities.MediaHistory
import com.ambientai.data.repositories.IMediaHistoryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusicPlayerService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val musicScanner: MusicScanner,
    private val mediaHistoryRepository: IMediaHistoryRepository
) {
    private var mediaPlayer: MediaPlayer? = null
    private var currentSong: Song? = null
    private var currentSongIndex: Int = -1
    private var playbackStartTime: Long = 0
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState

    companion object { private const val TAG = "MusicPlayer" }
    data class PlaybackState(val currentSong: Song? = null, val positionMs: Long = 0, val isPlaying: Boolean = false)
    fun execute(actionName: String, input: JSONObject) = when (actionName) { "music.play" -> play(input); "music.pause" -> pause(input); "music.resume" -> resume(input); "music.stop" -> stop(input); "music.next" -> next(input); "music.previous" -> previous(input); "music.getNowPlaying" -> getNowPlaying(input); else -> errorResult("Unknown action: $actionName") }
    private fun successResult(data: Map<String, Any?> = emptyMap()) = JSONObject().apply { put("success", true); data.forEach { (k, v) -> put(k, v) } }
    private fun errorResult(message: String) = JSONObject().apply { put("success", false); put("error", message) }
    private fun play(input: JSONObject): JSONObject {
        val query = input.optString("query", "")
        if (query.isBlank()) return errorResult("Query cannot be empty")
        val matches = musicScanner.findMatches(query)
        if (matches.isEmpty()) return errorResult("No songs found matching '$query'")
        val song = matches.first()
        Log.d(TAG, "▶ PLAYING: ${song.title} by ${song.artist}")
        playSong(song, matches)
        return successResult(mapOf("song" to song.title, "artist" to song.artist, "album" to song.album))
    }
    private fun pause(input: JSONObject): JSONObject {
        if (!isPlaying()) return errorResult("Nothing is playing")
        Log.d(TAG, "⏸ PAUSED")
        mediaPlayer?.pause()
        saveHistory()
        updateState()
        return successResult(mapOf("message" to "Paused"))
    }
    private fun resume(input: JSONObject): JSONObject {
        if (mediaPlayer == null || currentSong == null) return errorResult("No song to resume")
        if (isPlaying()) return errorResult("Already playing")
        Log.d(TAG, "▶ RESUMED")
        mediaPlayer?.start()
        playbackStartTime = System.currentTimeMillis()
        updateState()
        return successResult(mapOf("message" to "Resumed"))
    }
    private fun stop(input: JSONObject): JSONObject {
        if (!isPlaying()) return errorResult("Nothing is playing")
        Log.d(TAG, "■ STOPPED")
        mediaPlayer?.stop()
        saveHistory()
        cleanup()
        return successResult(mapOf("message" to "Stopped"))
    }
    private fun next(input: JSONObject): JSONObject {
        if (currentSong == null) return errorResult("No song playing")
        val songs = musicScanner.getSongs()
        if (songs.isEmpty()) return errorResult("No songs available")
        currentSongIndex = (currentSongIndex + 1) % songs.size
        val nextSong = songs[currentSongIndex]
        Log.d(TAG, "⏭ NEXT: ${nextSong.title}")
        playSong(nextSong, songs)
        return successResult(mapOf("song" to nextSong.title, "artist" to nextSong.artist))
    }
    private fun previous(input: JSONObject): JSONObject {
        if (currentSong == null) return errorResult("No song playing")
        val songs = musicScanner.getSongs()
        if (songs.isEmpty()) return errorResult("No songs available")
        currentSongIndex = if (currentSongIndex - 1 < 0) songs.size - 1 else currentSongIndex - 1
        val prevSong = songs[currentSongIndex]
        Log.d(TAG, "⏮ PREVIOUS: ${prevSong.title}")
        playSong(prevSong, songs)
        return successResult(mapOf("song" to prevSong.title, "artist" to prevSong.artist))
    }
    private fun getNowPlaying(input: JSONObject): JSONObject = currentSong?.let { successResult(mapOf("song" to it.title, "artist" to it.artist, "album" to it.album, "isPlaying" to isPlaying(), "response" to "${it.title} by ${it.artist}")) } ?: errorResult("Nothing is playing")
    private fun playSong(song: Song, allSongs: List<Song>) {
        cleanup()
        currentSong = song
        currentSongIndex = allSongs.indexOf(song)
        playbackStartTime = System.currentTimeMillis()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(song.path)
            prepare()
            start()
            setOnCompletionListener {
                Log.d(TAG, "✓ COMPLETED: ${song.title}")
                saveHistory()
                next(JSONObject())
            }
            setOnErrorListener { _, what, extra ->
                Log.e(TAG, "✖ PLAYBACK ERROR: what=$what extra=$extra")
                true
            }
        }
        updateState()
    }
    private fun saveHistory() = currentSong?.let { song -> mediaHistoryRepository.save(MediaHistory(mediaPath = song.path, mediaType = "music", timestamp = playbackStartTime, durationPlayedMs = System.currentTimeMillis() - playbackStartTime)) }
    private fun cleanup() { mediaPlayer?.release(); mediaPlayer = null; currentSong = null; updateState() }
    fun isPlaying() = mediaPlayer?.isPlaying ?: false
    private fun updateState() { _playbackState.value = PlaybackState(currentSong, mediaPlayer?.currentPosition?.toLong() ?: 0, isPlaying()) }
}
