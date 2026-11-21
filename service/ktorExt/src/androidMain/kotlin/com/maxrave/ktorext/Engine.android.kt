package com.maxrave.ktorext

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.engine.okhttp.OkHttp

private object SharedOkHttpEngineFactory : HttpClientEngineFactory<HttpClientEngineConfig> {
    override fun create(block: HttpClientEngineConfig.() -> Unit): HttpClientEngine =
        OkHttp.create {
            preconfigured = SharedOkHttp.baseClient
            block()
        }
}

actual fun getEngine(): HttpClientEngineFactory<HttpClientEngineConfig> = SharedOkHttpEngineFactory