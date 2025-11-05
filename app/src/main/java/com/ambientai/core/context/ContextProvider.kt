package com.ambientai.core.context

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Base class for runtime context providers.
 * Handles caching and refresh logic automatically.
 *
 * @param T The type of data this provider returns
 * @param key Unique identifier for this context (e.g., "location", "calendar")
 * @param cacheDurationMs How long cached values remain fresh (milliseconds)
 */
abstract class ContextProvider<T>(
    val key: String,
    private val cacheDurationMs: Long
) {
    private var lastUpdate: Long = 0
    private var cachedValue: T? = null
    private val mutex = Mutex()

    /**
     * Fetch fresh data from the source.
     * Called automatically when cache expires or on first access.
     */
    protected abstract suspend fun fetch(): T

    /**
     * Get the current value, refreshing if cache is stale.
     * Thread-safe with mutex to prevent concurrent refreshes.
     */
    suspend fun get(): T {
        mutex.withLock {
            val now = System.currentTimeMillis()
            if (cachedValue == null || now - lastUpdate > cacheDurationMs) {
                cachedValue = fetch()
                lastUpdate = now
            }
            return cachedValue!!
        }
    }

    /**
     * Force refresh on next access.
     * Useful when external event invalidates cached data.
     */
    fun invalidate() {
        lastUpdate = 0
    }
}