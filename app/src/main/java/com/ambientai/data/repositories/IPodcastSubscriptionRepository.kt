package com.ambientai.data.repositories

import com.ambientai.data.entities.PodcastSubscription
import kotlinx.coroutines.flow.Flow

interface IPodcastSubscriptionRepository {
    fun save(subscription: PodcastSubscription): PodcastSubscription
    fun getById(id: Long): PodcastSubscription?
    fun getByFeedUrl(feedUrl: String): PodcastSubscription?
    fun getAll(): List<PodcastSubscription>
    fun getAllFlow(): Flow<List<PodcastSubscription>>
    fun delete(id: Long)
    fun updateLastPolled(id: Long, timestamp: Long)
}
