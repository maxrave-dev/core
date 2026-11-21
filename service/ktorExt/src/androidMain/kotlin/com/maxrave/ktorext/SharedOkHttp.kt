package com.maxrave.ktorext

import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import java.net.Proxy
import java.util.concurrent.TimeUnit

/**
 * Process-wide OkHttp [ConnectionPool] and [Dispatcher] shared between Ktor (`getEngine()`)
 * and NewPipe / BravePipe extractors so TCP/TLS/HTTP2 connections to YouTube, InnerTube, and
 * GitHub/CDN hosts are reused across cold start and stream resolution.
 */
object SharedOkHttp {
    val baseClient: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
            .dispatcher(
                Dispatcher().apply {
                    maxRequests = 64
                    maxRequestsPerHost = 16
                },
            ).build()
    }

    fun clientFor(proxy: Proxy?): OkHttpClient =
        if (proxy == null) {
            baseClient
        } else {
            baseClient.newBuilder().proxy(proxy).build()
        }
}
