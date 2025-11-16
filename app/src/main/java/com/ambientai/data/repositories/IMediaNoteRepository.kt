package com.ambientai.data.repositories

import com.ambientai.data.entities.MediaNote

interface IMediaNoteRepository {
    fun save(note: MediaNote): MediaNote
    fun getById(id: Long): MediaNote?
    fun getBySegmentId(segmentId: Long): List<MediaNote>
    fun delete(id: Long)
}
