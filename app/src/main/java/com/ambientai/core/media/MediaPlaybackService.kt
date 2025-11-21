package com.ambientai.core.media

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.ambientai.core.music.PlaybackStateManager
import com.ambientai.data.repositories.IMediaRepository
import com.ambientai.data.repositories.IPodcastEpisodeRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaPlaybackService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playbackStateManager: PlaybackStateManager,
    private val mediaRepository: IMediaRepository,
    private val podcastEpisodeRepository: IPodcastEpisodeRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
        saveCurrentPosition()
        cleanup()
        currentItem = item
        playbackStartTime = System.currentTimeMillis()

        val savedPosition = if (item.id > 0) mediaRepository.getById(item.id)?.playbackPosition ?: 0L else 0L

        mediaPlayer = MediaPlayer().apply {
            setDataSource(playbackUrl)
            prepareAsync()
            setOnPreparedListener {
                if (savedPosition > 0 && savedPosition < duration) {
                    seekTo(savedPosition.toInt())
                    Log.d(TAG, "Resuming from position: ${savedPosition}ms")
                }
                start()
                _isPlaying = true
                playbackStateManager.setPlaying(true)
                Log.d(TAG, "✓ Playback started (playbackStateManager updated)")
            }
            setOnCompletionListener {
                if (item.id > 0) mediaRepository.updatePlaybackPosition(item.id, 0)
                _isPlaying = false
                playbackStateManager.setPlaying(false)
                Log.d(TAG, "⏹ Playback completed")
            }
            setOnErrorListener { _, what, extra ->
                Log.e(TAG, "❌ MediaPlayer error: what=$what, extra=$extra")
                _isPlaying = false
                playbackStateManager.setPlaying(false)
                true
            }
        }
    }

    fun pause() {
        saveCurrentPosition()
        mediaPlayer?.pause()
        _isPlaying = false
        playbackStateManager.setPlaying(false)
        Log.d(TAG, "⏸ PAUSED")
    }

    fun resume() {
        mediaPlayer?.start()
        _isPlaying = true
        playbackStateManager.setPlaying(true)
        playbackStartTime = System.currentTimeMillis()
        Log.d(TAG, "▶ RESUMED")
    }

    fun stop() {
        saveCurrentPosition()
        cleanup()
        Log.d(TAG, "⏹ STOPPED")
    }

    fun seekTo(positionMs: Long) {
        mediaPlayer?.seekTo(positionMs.toInt())
        Log.d(TAG, "Seeked to ${positionMs}ms")
    }

    fun seekRelative(deltaMs: Long) {
        val current = mediaPlayer?.currentPosition ?: return
        val newPosition = (current + deltaMs).coerceIn(0, mediaPlayer?.duration?.toLong() ?: 0)
        seekTo(newPosition)
    }

    private fun saveCurrentPosition() {
        val item = currentItem ?: return
        if (item.id <= 0) return
        val position = mediaPlayer?.currentPosition?.toLong() ?: return
        mediaRepository.updatePlaybackPosition(item.id, position)
        Log.d(TAG, "Saved position: ${position}ms for ${item.title}")
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
        playbackStateManager.setPlaying(false)
    }
}
