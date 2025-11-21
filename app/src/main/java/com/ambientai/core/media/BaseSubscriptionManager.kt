package com.ambientai.core.media

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Base implementation of SubscriptionManager that provides the common subscription workflow.
 * Platform implementations only need to provide:
 * - Data fetching methods (how to get info from the source)
 * - Entity creation/persistence methods
 * - Repository access
 */
abstract class BaseSubscriptionManager<S : Subscription> : SubscriptionManager<S> {
    protected abstract val tag: String

    // Core workflow: subscribe
    override suspend fun subscribe(identifier: String): S? = withContext(Dispatchers.IO) {
        // Check if already subscribed
        getByIdentifier(identifier)?.let {
            Log.d(tag, "Already subscribed to: ${it.title}")
            return@withContext it
        }

        // Fetch subscription info from platform
        val info = fetchSubscriptionInfo(identifier) ?: run {
            Log.e(tag, "Failed to fetch subscription info: $identifier")
            return@withContext null
        }

        // Create and save subscription
        val subscription = createAndSaveSubscription(info)

        // Fetch and save initial content
        val content = fetchContentInfo(identifier)
        val savedCount = saveNewContent(subscription, content)

        Log.d(tag, "Subscribed to: ${subscription.title} ($savedCount items)")
        subscription
    }

    // Core workflow: unsubscribe
    override suspend fun unsubscribe(subscriptionId: Long) {
        withContext(Dispatchers.IO) {
            deleteContent(subscriptionId)
            deleteSubscription(subscriptionId)
            Log.d(tag, "Unsubscribed: $subscriptionId")
        }
    }

    // Core workflow: refresh all
    override suspend fun refresh(): Int = withContext(Dispatchers.IO) {
        var totalNew = 0
        getAllSubscriptionsList().forEach { subscription ->
            totalNew += refreshSubscription(subscription.id)
        }
        Log.d(tag, "Refreshed all ${platform.name} subscriptions, found $totalNew new items")
        totalNew
    }

    // Core workflow: refresh single subscription
    override suspend fun refreshSubscription(subscriptionId: Long): Int = withContext(Dispatchers.IO) {
        val subscription = getSubscriptionById(subscriptionId) ?: return@withContext 0
        val identifier = getSubscriptionIdentifier(subscription)
        val content = fetchContentInfo(identifier)
        val newCount = saveNewContent(subscription, content)
        onRefreshComplete(subscription)
        Log.d(tag, "Refreshed ${subscription.title}, found $newCount new items")
        newCount
    }

    override fun getSubscription(subscriptionId: Long): S? = getSubscriptionById(subscriptionId)

    // Platform-specific: Data fetching (the "how")
    protected abstract suspend fun fetchSubscriptionInfo(identifier: String): SubscriptionInfo?
    protected abstract suspend fun fetchContentInfo(identifier: String): List<ContentInfo>

    // Platform-specific: Entity management
    protected abstract fun createAndSaveSubscription(info: SubscriptionInfo): S
    protected abstract fun saveNewContent(subscription: S, content: List<ContentInfo>): Int
    protected abstract fun deleteContent(subscriptionId: Long)
    protected abstract fun deleteSubscription(subscriptionId: Long)

    // Platform-specific: Repository access
    protected abstract fun getByIdentifier(identifier: String): S?
    protected abstract fun getAllSubscriptionsList(): List<S>
    protected abstract fun getSubscriptionById(id: Long): S?
    protected abstract fun getSubscriptionIdentifier(subscription: S): String

    // Optional hook for post-refresh actions
    protected open fun onRefreshComplete(subscription: S) {}
}

// Common data transfer objects for intermediate representation
data class SubscriptionInfo(
    val identifier: String,
    val title: String,
    val description: String,
    val thumbnailUrl: String?,
    val metadata: Map<String, Any?> = emptyMap()
)

data class ContentInfo(
    val identifier: String,
    val title: String,
    val description: String,
    val thumbnailUrl: String?,
    val duration: Long,
    val publishDate: Long,
    val metadata: Map<String, Any?> = emptyMap()
)
