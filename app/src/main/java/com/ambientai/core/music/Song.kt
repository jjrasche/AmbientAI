package com.ambientai.core.music

data class Song(
    val path: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val trackNumber: Int?
)
