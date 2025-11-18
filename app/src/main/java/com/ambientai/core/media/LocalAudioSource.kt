package com.ambientai.core.media

import android.content.Context
import android.provider.MediaStore
import android.util.Log
import com.ambientai.core.music.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalAudioSource @Inject constructor(
    @ApplicationContext private val context: Context
) : MediaSource {
    companion object {
        private const val TAG = "LocalAudioSource"
        private const val MUSIC_DIRECTORY = "/storage/emulated/0/Music"
    }

    private var songs = mutableListOf<Song>()
    private var isScanned = false

    override suspend fun search(query: String): List<MediaItem> = withContext(Dispatchers.IO) {
        if (!isScanned) scanLibrary()
        val lowerQuery = query.lowercase()
        songs.filter {
            it.artist.lowercase().contains(lowerQuery) ||
            it.album.lowercase().contains(lowerQuery) ||
            it.title.lowercase().contains(lowerQuery)
        }.map { it.toMediaItem() }
    }

    override suspend fun getPlaybackUrl(item: MediaItem) = item.playbackUrl

    override suspend fun download(item: MediaItem) = item.playbackUrl

    override fun canHandle(item: MediaItem) = item.sourceType == MediaSourceType.LOCAL_AUDIO

    fun getSongs(): List<Song> {
        if (!isScanned) scanLibrary()
        return songs
    }

    fun findMatches(query: String): List<Song> {
        val lowerQuery = query.lowercase()
        return songs.filter {
            it.artist.lowercase().contains(lowerQuery) ||
            it.album.lowercase().contains(lowerQuery) ||
            it.title.lowercase().contains(lowerQuery)
        }
    }

    private fun scanLibrary(): Int {
        Log.d(TAG, "▶ SCANNING LOCAL AUDIO LIBRARY")
        songs.clear()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.TRACK
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val selectionArgs = null

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Audio.Media.ARTIST} ASC, ${MediaStore.Audio.Media.ALBUM} ASC, ${MediaStore.Audio.Media.TRACK} ASC"
        )?.use { cursor ->
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val pathCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val trackCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)

            while (cursor.moveToNext()) {
                songs.add(Song(
                    path = cursor.getString(pathCol),
                    title = cursor.getString(titleCol) ?: "Unknown",
                    artist = cursor.getString(artistCol) ?: "Unknown Artist",
                    album = cursor.getString(albumCol) ?: "Unknown Album",
                    durationMs = cursor.getLong(durationCol),
                    trackNumber = cursor.getInt(trackCol).takeIf { it > 0 }
                ))
            }
        }

        isScanned = true
        Log.d(TAG, "✓ SCANNED ${songs.size} LOCAL AUDIO FILES")
        return songs.size
    }
}

fun Song.toMediaItem() = MediaItem(
    id = 0,
    title = title,
    creator = artist,
    sourceType = MediaSourceType.LOCAL_AUDIO,
    mediaType = MediaType.MUSIC,
    playbackUrl = path,
    duration = durationMs,
    metadata = MediaMetadata(
        album = album,
        trackNumber = trackNumber
    )
)
