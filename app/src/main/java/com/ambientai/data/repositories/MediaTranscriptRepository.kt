package com.ambientai.data.repositories

import com.ambientai.data.entities.MediaTranscript
import com.ambientai.data.entities.MediaTranscript_
import io.objectbox.Box
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaTranscriptRepository @Inject constructor(
    private val boxStore: BoxStore
) : IMediaTranscriptRepository {
    private val box: Box<MediaTranscript> = boxStore.boxFor()
    override fun save(transcript: MediaTranscript) = transcript.also { box.put(it) }
    override fun getById(id: Long) = box.get(id)
    override fun getByMediaId(mediaId: Long) = box.query().equal(MediaTranscript_.mediaId, mediaId).build().findFirst()
    override fun getAllByMediaId(mediaId: Long) = box.query().equal(MediaTranscript_.mediaId, mediaId).build().find()
    override fun getAll() = box.all
    override fun delete(id: Long) { box.remove(id) }
}
