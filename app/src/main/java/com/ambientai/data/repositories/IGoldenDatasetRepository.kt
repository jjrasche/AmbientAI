package com.ambientai.data.repositories

import com.ambientai.data.entities.GoldenDataset
import kotlinx.coroutines.flow.Flow

interface IGoldenDatasetRepository {
    fun save(dataset: GoldenDataset): GoldenDataset
    fun getById(id: Long): GoldenDataset?
    fun update(dataset: GoldenDataset)
    fun delete(id: Long)
    fun deleteAll()
    fun count(): Long
    fun getAll(): List<GoldenDataset>
    fun getRecent(limit: Int): List<GoldenDataset>
    fun getByTimeRange(startTime: Long, endTime: Long): List<GoldenDataset>
    fun markAccurate(id: Long, accurate: Boolean)
    fun getAllFlow(): Flow<List<GoldenDataset>>
}
