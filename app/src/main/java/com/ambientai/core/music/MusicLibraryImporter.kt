package com.ambientai.core.music

import android.util.Log
import com.ambientai.data.entities.Media
import com.ambientai.data.repositories.IMediaRepository
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusicLibraryImporter @Inject constructor(
    private val musicScanner: MusicScanner,
    private val mediaRepo: IMediaRepository
) {
    companion object { private const val TAG = "MusicLibraryImporter" }
    fun importAllSongs(): ImportResult {
        Log.d(TAG, "🎵 Starting music library import")
        val songs = musicScanner.getSongs()
        if (songs.isEmpty()) { Log.w(TAG, "⚠ No songs found in music folder"); return ImportResult(0, 0, 0) }
        Log.d(TAG, "📀 Found ${songs.size} songs, checking for existing Media entities...")
        val existingMedia = mediaRepo.getBySourceType("local_music")
        val existingPaths = existingMedia.map { it.localFilePath }.toSet()
        val newSongs = songs.filter { it.path !in existingPaths }
        val skippedCount = songs.size - newSongs.size
        Log.d(TAG, "💾 Creating ${newSongs.size} new Media entities (${skippedCount} already exist)")
        val createdMedia = newSongs.map { song ->
            Media(
                title = song.title,
                sourceType = "local_music",
                mediaType = "audio",
                sourceUrl = song.path,
                duration = song.durationMs,
                channelName = song.artist,
                localFilePath = song.path,
                createdDate = System.currentTimeMillis()
            ).also { mediaRepo.save(it) }
        }
        Log.d(TAG, "✅ Import complete: ${createdMedia.size} created, ${skippedCount} skipped, ${songs.size} total")
        return ImportResult(songs.size, createdMedia.size, skippedCount)
    }
    data class ImportResult(val totalSongs: Int, val created: Int, val skipped: Int)
}
