package com.ambientai.core.music

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackStateManager @Inject constructor() {
    @Volatile
    private var isPlaying: Boolean = false
    @Volatile
    private var currentSong: Song? = null

    fun setPlaying(playing: Boolean) { isPlaying = playing }
    fun isPlaying(): Boolean = isPlaying
    fun setCurrentSong(song: Song?) { currentSong = song }
    fun getCurrentSong(): Song? = currentSong
}
