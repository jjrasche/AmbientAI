package com.ambientai.data.repositories

import com.ambientai.data.entities.MediaPlaybackSession

interface IMediaPlaybackSessionRepository {
    fun save(session: MediaPlaybackSession): MediaPlaybackSession
    fun getById(id: Long): MediaPlaybackSession?
    fun getByMediaId(mediaId: Long): List<MediaPlaybackSession>
    fun getCurrentSession(mediaId: Long): MediaPlaybackSession?
    fun incrementQuestions(id: Long)
    fun delete(id: Long)
}
