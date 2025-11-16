package com.ambientai.data.repositories

import com.ambientai.data.entities.Media
import kotlinx.coroutines.flow.Flow

interface IMediaRepository {
    fun save(media: Media): Media
    fun getById(id: Long): Media?
    fun update(media: Media)
    fun delete(id: Long)
    fun getAll(): List<Media>
    fun getBySourceType(sourceType: String): List<Media>
    fun getByMediaType(mediaType: String): List<Media>
    fun updatePlaybackPosition(id: Long, positionMs: Long)
    fun getAllMedia(): Flow<List<Media>>
}
