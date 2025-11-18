package com.ambientai.core.music

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackStateManager @Inject constructor() {
    @Volatile
    private var isPlaying: Boolean = false

    fun setPlaying(playing: Boolean) { isPlaying = playing }
    fun isPlaying(): Boolean = isPlaying
}
