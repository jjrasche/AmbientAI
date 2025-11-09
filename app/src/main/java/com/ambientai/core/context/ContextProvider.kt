package com.ambientai.core.context

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

abstract class ContextProvider<T>(val key: String, private val cacheDurationMs: Long) {
    private var lastUpdate: Long = 0
    private var cachedValue: T? = null
    private val mutex = Mutex()

    protected abstract suspend fun fetch(): T
    suspend fun get(): T = mutex.withLock {
        System.currentTimeMillis().let { now ->
            if (cachedValue == null || now - lastUpdate > cacheDurationMs) {
                cachedValue = fetch()
                lastUpdate = now
            }
            cachedValue!!
        }
    }
    fun invalidate() { lastUpdate = 0 }
}
