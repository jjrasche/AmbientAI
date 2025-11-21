package com.ambientai.data.repositories

import com.ambientai.data.entities.PodcastSubscription
import com.ambientai.data.entities.PodcastSubscription_
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
class PodcastSubscriptionRepository @Inject constructor(
    boxStore: BoxStore
) : IPodcastSubscriptionRepository {
    private val box: Box<PodcastSubscription> = boxStore.boxFor()
    override fun save(subscription: PodcastSubscription) = subscription.also { box.put(it) }
    override fun getById(id: Long) = box.get(id)
    override fun getByFeedUrl(feedUrl: String) = box.query().equal(PodcastSubscription_.feedUrl, feedUrl, StringOrder.CASE_INSENSITIVE).build().findFirst()
    override fun getAll() = box.query().order(PodcastSubscription_.subscribedAt).build().find()
    override fun getAllFlow(): Flow<List<PodcastSubscription>> = callbackFlow {
        val subscription = box.query().order(PodcastSubscription_.subscribedAt).build().subscribe().observer { trySend(it) }
        awaitClose { subscription.cancel() }
    }
    override fun delete(id: Long) { box.remove(id) }
    override fun updateLastPolled(id: Long, timestamp: Long) { box.get(id)?.let { it.lastPolledAt = timestamp; box.put(it) } }
}
