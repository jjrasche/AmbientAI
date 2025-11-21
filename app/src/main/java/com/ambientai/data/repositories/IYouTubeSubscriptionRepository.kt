package com.ambientai.data.repositories

import com.ambientai.data.entities.YouTubeSubscription
import kotlinx.coroutines.flow.Flow

interface IYouTubeSubscriptionRepository {
    fun save(subscription: YouTubeSubscription): YouTubeSubscription
    fun getById(id: Long): YouTubeSubscription?
    fun getByChannelId(channelId: String): YouTubeSubscription?
    fun getAll(): List<YouTubeSubscription>
    fun getAllFlow(): Flow<List<YouTubeSubscription>>
    fun delete(id: Long)
}
