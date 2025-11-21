package com.ambientai.core.media

import kotlinx.coroutines.flow.Flow

interface SubscriptionManager<S : Subscription> {
    val platform: Platform
    suspend fun subscribe(identifier: String): S?
    suspend fun unsubscribe(subscriptionId: Long)
    suspend fun refresh(): Int
    suspend fun refreshSubscription(subscriptionId: Long): Int
    fun getSubscriptions(): Flow<List<S>>
    fun getSubscription(subscriptionId: Long): S?
}
