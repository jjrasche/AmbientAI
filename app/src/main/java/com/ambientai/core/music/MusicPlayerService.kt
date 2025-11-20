package com.ambientai.core.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import com.ambientai.MainActivity
import com.ambientai.R
import com.ambientai.data.entities.MediaHistory
import com.ambientai.data.repositories.IMediaHistoryRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

@AndroidEntryPoint
class MusicPlayerService : Service() {
    @Inject lateinit var musicScanner: MusicScanner
    @Inject lateinit var mediaHistoryRepository: IMediaHistoryRepository
    @Inject lateinit var playbackStateManager: PlaybackStateManager
    private var mediaPlayer: MediaPlayer? = null
    private var currentSong: Song? = null
    private var currentSongIndex: Int = -1
    private var playbackStartTime: Long = 0
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState
    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private val binder = LocalBinder()
    private var fadeJob: Job? = null

    companion object {
        private const val TAG = "MusicPlayer"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "music_playback_channel"
        const val ACTION_PLAY = "com.ambientai.music.PLAY"
        const val ACTION_PAUSE = "com.ambientai.music.PAUSE"
        const val ACTION_NEXT = "com.ambientai.music.NEXT"
        const val ACTION_PREVIOUS = "com.ambientai.music.PREVIOUS"
        const val ACTION_STOP = "com.ambientai.music.STOP"
        const val ACTION_GET_NOW_PLAYING = "com.ambientai.music.GET_NOW_PLAYING"
    }

    data class PlaybackState(val currentSong: Song? = null, val positionMs: Long = 0, val isPlaying: Boolean = false)

    inner class LocalBinder : Binder() { fun getService() = this@MusicPlayerService }
    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
        initMediaSession()
        musicScanner.getSongs()
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val inputJson = intent?.getStringExtra("input")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        when (intent?.action) {
            ACTION_PLAY -> {
                if (inputJson.has("query")) play(inputJson)
                else if (!isPlaying()) resume(JSONObject())
            }
            ACTION_PAUSE -> pause(JSONObject())
            ACTION_NEXT -> next(JSONObject())
            ACTION_PREVIOUS -> previous(JSONObject())
            ACTION_STOP -> { stop(JSONObject()); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            ACTION_GET_NOW_PLAYING -> getNowPlaying(JSONObject())
        }
        MediaButtonReceiver.handleIntent(mediaSession, intent)
        return START_STICKY
    }
    override fun onBind(intent: Intent?): IBinder = binder
    override fun onDestroy() {
        super.onDestroy()
        cleanup()
        mediaSession.release()
        abandonAudioFocus()
    }

    private fun successResult(data: Map<String, Any?> = emptyMap()) = JSONObject().apply { put("success", true); data.forEach { (k, v) -> put(k, v) } }
    private fun errorResult(message: String) = JSONObject().apply { put("success", false); put("error", message) }
    private fun play(input: JSONObject): JSONObject {
        val query = input.optString("query", "")
        if (query.isBlank()) return errorResult("Query cannot be empty")
        if (!requestAudioFocus()) return errorResult("Could not gain audio focus")
        val matches = musicScanner.findMatches(query)
        if (matches.isEmpty()) return errorResult("No songs found matching '$query'")
        val song = matches.first()
        val lowerQuery = query.lowercase()
        val isExactMatch = song.artist.lowercase().contains(lowerQuery) || song.album.lowercase().contains(lowerQuery) || song.title.lowercase().contains(lowerQuery)
        if (!isExactMatch) return errorResult("No exact match found for '$query'")
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
        if (!requestAudioFocus()) return errorResult("Could not gain audio focus")
        Log.d(TAG, "▶ RESUMED")
        mediaPlayer?.start()
        fadeInVolume()
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
    private fun cleanup() { fadeJob?.cancel(); fadeJob = null; mediaPlayer?.release(); mediaPlayer = null; currentSong = null; updateState() }
    fun isPlaying() = mediaPlayer?.isPlaying ?: false
    fun getMediaPlayer(): MediaPlayer? = mediaPlayer
    fun stop() = stop(JSONObject())
    fun loadAndPlay(filePath: String) {
        cleanup()
        playbackStartTime = System.currentTimeMillis()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(filePath)
            prepare()
            start()
            setOnCompletionListener {
                Log.d(TAG, "✓ COMPLETED: $filePath")
                saveHistory()
            }
            setOnErrorListener { _, what, extra ->
                Log.e(TAG, "✖ PLAYBACK ERROR: what=$what extra=$extra")
                true
            }
        }
        updateState()
    }
    private fun updateState() {
        val playing = isPlaying()
        Log.d(TAG, "📊 updateState: playing=$playing, currentSong=${currentSong?.title}")
        _playbackState.value = PlaybackState(currentSong, mediaPlayer?.currentPosition?.toLong() ?: 0, playing)
        playbackStateManager.setPlaying(playing)
        playbackStateManager.setCurrentSong(currentSong)
        updateMediaSession()
        updateNotification()
    }
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Music Playback", NotificationManager.IMPORTANCE_LOW).apply { description = "Music player controls"; setShowBadge(false) }
            )
        }
    }
    private fun initMediaSession() {
        mediaSession = MediaSessionCompat(this, TAG).apply {
            setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { resume(JSONObject()) }
                override fun onPause() { pause(JSONObject()) }
                override fun onSkipToNext() { next(JSONObject()) }
                override fun onSkipToPrevious() { previous(JSONObject()) }
                override fun onStop() { stop(JSONObject()); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
            })
            isActive = true
        }
    }
    private fun updateMediaSession() {
        currentSong?.let { song ->
            mediaSession.setMetadata(MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.durationMs)
                .build())
        }
        val state = if (isPlaying()) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        mediaSession.setPlaybackState(PlaybackStateCompat.Builder()
            .setState(state, mediaPlayer?.currentPosition?.toLong() ?: 0, 1.0f)
            .setActions(PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_SKIP_TO_NEXT or PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or PlaybackStateCompat.ACTION_STOP)
            .build())
    }
    private fun updateNotification() {
        if (currentSong == null) return
        val notification = createNotification()
        if (isPlaying()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
        }
    }
    private fun createNotification(): Notification {
        val song = currentSong ?: return NotificationCompat.Builder(this, CHANNEL_ID).build()
        val playPauseAction = if (isPlaying()) {
            NotificationCompat.Action(android.R.drawable.ic_media_pause, "Pause", PendingIntent.getService(this, 2, Intent(this, MusicPlayerService::class.java).apply { action = ACTION_PAUSE }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            NotificationCompat.Action(android.R.drawable.ic_media_play, "Play", PendingIntent.getService(this, 2, Intent(this, MusicPlayerService::class.java).apply { action = ACTION_PLAY }, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(song.title)
            .setContentText("${song.artist} • ${song.album}")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setStyle(androidx.media.app.NotificationCompat.MediaStyle().setMediaSession(mediaSession.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .addAction(android.R.drawable.ic_media_previous, "Previous", PendingIntent.getService(this, 1, Intent(this, MusicPlayerService::class.java).apply { action = ACTION_PREVIOUS }, PendingIntent.FLAG_IMMUTABLE))
            .addAction(playPauseAction)
            .addAction(android.R.drawable.ic_media_next, "Next", PendingIntent.getService(this, 3, Intent(this, MusicPlayerService::class.java).apply { action = ACTION_NEXT }, PendingIntent.FLAG_IMMUTABLE))
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(isPlaying())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
    }
    private fun fadeInVolume() {
        fadeJob?.cancel()
        fadeJob = CoroutineScope(Dispatchers.Main).launch {
            val steps = 20
            val stepDuration = 50L
            for (i in 0..steps) {
                val volume = (i.toFloat() / steps)
                mediaPlayer?.setVolume(volume, volume)
                delay(stepDuration)
            }
        }
    }
    private fun requestAudioFocus(): Boolean {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).setUsage(AudioAttributes.USAGE_MEDIA).build()).setOnAudioFocusChangeListener { focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS -> pause(JSONObject())
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> mediaPlayer?.setVolume(0.2f, 0.2f)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> mediaPlayer?.setVolume(0.2f, 0.2f)
                    AudioManager.AUDIOFOCUS_GAIN -> { if (!isPlaying() && currentSong != null) resume(JSONObject()) else fadeInVolume() }
                }
            }.build()
            audioManager.requestAudioFocus(audioFocusRequest!!)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus({ focusChange ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS -> pause(JSONObject())
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> mediaPlayer?.setVolume(0.2f, 0.2f)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> mediaPlayer?.setVolume(0.2f, 0.2f)
                    AudioManager.AUDIOFOCUS_GAIN -> { if (!isPlaying() && currentSong != null) resume(JSONObject()) else fadeInVolume() }
                }
            }, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }
    private fun abandonAudioFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }
}
