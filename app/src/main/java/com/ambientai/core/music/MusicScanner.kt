package com.ambientai.core.music

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusicScanner @Inject constructor(@ApplicationContext private val context: Context) {
    private val songs = mutableListOf<Song>()
    private var isScanned = false

    companion object { private const val TAG = "MusicScanner" }
    fun execute(actionName: String, input: JSONObject) = when (actionName) { "music.scan" -> scan(input); "music.search" -> search(input); "music.listAll" -> listAll(input); else -> errorResult("Unknown action: $actionName") }
    private fun successResult(data: Map<String, Any?> = emptyMap()) = JSONObject().apply { put("success", true); data.forEach { (k, v) -> put(k, v) } }
    private fun errorResult(message: String) = JSONObject().apply { put("success", false); put("error", message) }
    fun getSongs(): List<Song> { if (!isScanned) scanLibrary(); return songs }
    private fun scan(input: JSONObject): JSONObject {
        val count = scanLibrary()
        return successResult(mapOf("songsFound" to count, "message" to "Scanned $count songs"))
    }
    private fun search(input: JSONObject): JSONObject {
        val query = input.optString("query", "")
        if (query.isBlank()) return errorResult("Query cannot be empty")
        if (!isScanned) scanLibrary()
        val matches = findMatches(query)
        return successResult(mapOf("matches" to matches.size, "songs" to matches.map { mapOf("title" to it.title, "artist" to it.artist, "album" to it.album) }))
    }
    private fun listAll(input: JSONObject): JSONObject {
        if (!isScanned) scanLibrary()
        val songList = songs.joinToString("\n\n") { "${it.artist} - ${it.title}\nAlbum: ${it.album}" }
        val summary = "Found ${songs.size} songs"
        return successResult(mapOf("totalSongs" to songs.size, "songList" to songList, "summary" to summary))
    }
    private fun scanLibrary(): Int {
        Log.d(TAG, "▶ SCANNING MUSIC LIBRARY")
        if (!hasPermission()) { Log.e(TAG, "✖ Missing storage permission"); return 0 }
        songs.clear()
        val musicDirectory = "/storage/emulated/0/Documents/Second Brain/Second Brain/resource/attachements/music"
        val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DATA, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.TRACK)
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DATA} LIKE ?"
        val selectionArgs = arrayOf("$musicDirectory%")
        context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, selection, selectionArgs, "${MediaStore.Audio.Media.ARTIST} ASC, ${MediaStore.Audio.Media.ALBUM} ASC, ${MediaStore.Audio.Media.TRACK} ASC")?.use { cursor ->
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
        Log.d(TAG, "✓ SCANNED ${songs.size} SONGS")
        return songs.size
    }
    fun findMatches(query: String): List<Song> {
        val lowerQuery = query.lowercase()
        val exactMatches = songs.filter { it.artist.lowercase().contains(lowerQuery) || it.album.lowercase().contains(lowerQuery) || it.title.lowercase().contains(lowerQuery) }
        return exactMatches.ifEmpty { songs.sortedBy { levenshteinDistance(lowerQuery, it.title.lowercase()) }.take(20) }
    }
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val costs = IntArray(s2.length + 1) { it }
        for (i in 1..s1.length) {
            costs[0] = i
            var lastValue = i - 1
            for (j in 1..s2.length) {
                val newValue = if (s1[i - 1] == s2[j - 1]) lastValue else 1 + minOf(lastValue, costs[j], costs[j - 1])
                lastValue = costs[j]
                costs[j] = newValue
            }
        }
        return costs[s2.length]
    }
    private fun hasPermission() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED else ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
}
