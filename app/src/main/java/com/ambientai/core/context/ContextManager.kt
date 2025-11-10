package com.ambientai.core.context

import java.util.concurrent.ConcurrentHashMap

object ContextManager {
    private val providers = ConcurrentHashMap<String, ContextProvider<*>>()

    fun <T> register(provider: ContextProvider<T>) = run { providers[provider.key] = provider }
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> get(key: String): T? = (providers[key] as? ContextProvider<T>)?.get()
    suspend fun getAll() = providers.mapValues { it.value.get() }
    fun invalidate(key: String) = providers[key]?.invalidate()
    fun clearAllProviders() = providers.clear()
}
