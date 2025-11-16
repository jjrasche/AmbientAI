package com.ambientai.data.repositories

import com.ambientai.data.entities.MediaPlaybackSession
import com.ambientai.data.entities.MediaPlaybackSession_
import io.objectbox.Box
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import io.objectbox.query.OrderFlags
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaPlaybackSessionRepository @Inject constructor(
    private val boxStore: BoxStore
) : IMediaPlaybackSessionRepository {
    private val box: Box<MediaPlaybackSession> = boxStore.boxFor()
    override fun save(session: MediaPlaybackSession) = session.also { box.put(it) }
    override fun getById(id: Long) = box.get(id)
    override fun getByMediaId(mediaId: Long) = box.query().equal(MediaPlaybackSession_.mediaId, mediaId).order(MediaPlaybackSession_.startTime, OrderFlags.DESCENDING).build().find()
    override fun getCurrentSession(mediaId: Long) = box.query().equal(MediaPlaybackSession_.mediaId, mediaId).order(MediaPlaybackSession_.startTime, OrderFlags.DESCENDING).build().findFirst()
    override fun incrementQuestions(id: Long) { box.get(id)?.let { it.questionsAsked++; box.put(it) } }
    override fun delete(id: Long) { box.remove(id) }
}
