package com.ambientai.data.repositories

import com.ambientai.data.entities.MediaHistory
import kotlinx.coroutines.flow.Flow

interface IMediaHistoryRepository {
    fun save(history: MediaHistory): MediaHistory
    fun getById(id: Long): MediaHistory?
    fun delete(id: Long)
    fun getAll(): List<MediaHistory>
    fun count(): Long
    fun getByMediaType(mediaType: String): List<MediaHistory>
    fun getRecent(limit: Int): List<MediaHistory>
    fun getByTimeRange(startTime: Long, endTime: Long): List<MediaHistory>
    fun getAllHistory(): Flow<List<MediaHistory>>
}
