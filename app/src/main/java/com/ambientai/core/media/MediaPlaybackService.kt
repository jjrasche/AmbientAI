package com.ambientai.core.media

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaPlaybackService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "MediaPlaybackService"
    }

    private var mediaPlayer: MediaPlayer? = null
    private var currentItem: MediaItem? = null
    private var currentPlaylist: List<MediaItem> = emptyList()
    private var currentIndex: Int = -1
    private var playbackStartTime: Long = 0

    @Volatile
    private var _isPlaying: Boolean = false

    suspend fun play(item: MediaItem, playbackUrl: String) {
        Log.d(TAG, "▶ PLAYING: ${item.title} by ${item.creator} from ${item.sourceType}")
        cleanup()
        currentItem = item
        playbackStartTime = System.currentTimeMillis()

        mediaPlayer = MediaPlayer().apply {
            setDataSource(playbackUrl)
            prepareAsync()
            setOnPreparedListener {
                start()
                _isPlaying = true
                Log.d(TAG, "✓ Playback started")
            }
            setOnCompletionListener {
                _isPlaying = false
                Log.d(TAG, "⏹ Playback completed")
            }
            setOnErrorListener { _, what, extra ->
                Log.e(TAG, "❌ MediaPlayer error: what=$what, extra=$extra")
                _isPlaying = false
                true
            }
        }
    }

    fun pause() {
        mediaPlayer?.pause()
        _isPlaying = false
        Log.d(TAG, "⏸ PAUSED")
    }

    fun resume() {
        mediaPlayer?.start()
        _isPlaying = true
        playbackStartTime = System.currentTimeMillis()
        Log.d(TAG, "▶ RESUMED")
    }

    fun stop() {
        cleanup()
        Log.d(TAG, "⏹ STOPPED")
    }

    fun next() {
        if (currentPlaylist.isEmpty()) {
            Log.w(TAG, "No playlist for next")
            return
        }
        if (currentIndex < currentPlaylist.size - 1) {
            currentIndex++
            // Note: Caller must get playback URL and call play()
        }
    }

    fun previous() {
        if (currentPlaylist.isEmpty()) {
            Log.w(TAG, "No playlist for previous")
            return
        }
        if (currentIndex > 0) {
            currentIndex--
            // Note: Caller must get playback URL and call play()
        }
    }

    fun setPlaylist(items: List<MediaItem>, startIndex: Int = 0) {
        currentPlaylist = items
        currentIndex = startIndex
    }

    fun getCurrentItem() = currentItem
    fun getCurrentIndex() = currentIndex
    fun getPlaylist() = currentPlaylist
    fun isPlaying() = _isPlaying
    fun getDuration() = mediaPlayer?.duration?.toLong() ?: 0L
    fun getCurrentPosition() = mediaPlayer?.currentPosition?.toLong() ?: 0L

    private fun cleanup() {
        mediaPlayer?.release()
        mediaPlayer = null
        currentItem = null
        _isPlaying = false
    }
}
