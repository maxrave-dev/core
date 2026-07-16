package com.my.kizzy.rpc

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object ArtworkCache {
    private val cache = mutableMapOf<String, String>()
    private val mutex = Mutex()

    // The whole lookup+fetch runs under one mutex: concurrent presence updates for the same
    // artwork can't fan out into duplicate external-asset HTTP calls, and the plain map is
    // never touched from two threads at once (commonMain — no java.util.concurrent here).
    suspend fun getOrFetch(key: String, fetch: suspend () -> String?): String? =
        mutex.withLock {
            cache[key] ?: fetch()?.also { cache[key] = it }
        }
}
