package com.ambientai.data.repositories

import com.ambientai.data.entities.YouTubeSubscription
import com.ambientai.data.entities.YouTubeSubscription_
import io.objectbox.Box
import io.objectbox.BoxStore
import io.objectbox.kotlin.boxFor
import io.objectbox.query.QueryBuilder.StringOrder
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class YouTubeSubscriptionRepository @Inject constructor(boxStore: BoxStore) : IYouTubeSubscriptionRepository {
    private val box: Box<YouTubeSubscription> = boxStore.boxFor()
    override fun save(subscription: YouTubeSubscription) = subscription.also { box.put(it) }
    override fun getById(id: Long) = box.get(id)
    override fun getByChannelId(channelId: String) = box.query().equal(YouTubeSubscription_.channelId, channelId, StringOrder.CASE_SENSITIVE).build().findFirst()
    override fun getAll() = box.all
    override fun getAllFlow(): Flow<List<YouTubeSubscription>> = callbackFlow {
        val query = box.query().build()
        val observer = query.subscribe().observer { trySend(it) }
        awaitClose { observer.cancel() }
    }
    override fun delete(id: Long) { box.remove(id) }
}
