package com.ambientai.data.repositories

import com.ambientai.data.entities.MediaTranscript

interface IMediaTranscriptRepository {
    fun save(transcript: MediaTranscript): MediaTranscript
    fun getById(id: Long): MediaTranscript?
    fun getByMediaId(mediaId: Long): MediaTranscript?
    fun getAllByMediaId(mediaId: Long): List<MediaTranscript>
    fun getAll(): List<MediaTranscript>
    fun delete(id: Long)
}
