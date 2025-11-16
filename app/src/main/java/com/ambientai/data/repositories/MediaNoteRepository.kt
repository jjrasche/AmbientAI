package com.ambientai.data.repositories

import com.ambientai.data.entities.MediaNote
import com.ambientai.data.entities.MediaNote_
import io.objectbox.Box
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import io.objectbox.query.OrderFlags
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaNoteRepository @Inject constructor(
    private val boxStore: BoxStore
) : IMediaNoteRepository {
    private val box: Box<MediaNote> = boxStore.boxFor()
    override fun save(note: MediaNote) = note.also { box.put(it) }
    override fun getById(id: Long) = box.get(id)
    override fun getBySegmentId(segmentId: Long) = box.query().equal(MediaNote_.segmentId, segmentId).order(MediaNote_.createdDate, OrderFlags.DESCENDING).build().find()
    override fun delete(id: Long) { box.remove(id) }
}
