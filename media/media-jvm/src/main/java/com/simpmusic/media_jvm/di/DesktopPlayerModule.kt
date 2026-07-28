package com.simpmusic.media_jvm.di

import com.maxrave.common.Config.SERVICE_SCOPE
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.repository.CacheRepository
import com.simpmusic.media_jvm.download.DownloadUtils
import com.simpmusic.media_jvm.mpv.MpvPlayerAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import org.koin.core.context.loadKoinModules
import org.koin.core.qualifier.named
import org.koin.dsl.module

private val desktopPlayerModule =
    module {
        single<CoroutineScope>(qualifier = named(SERVICE_SCOPE)) {
            // Single-thread dispatcher: serializes all player operations onto one thread so the
            // adapter's state machine never races with itself. libmpv is thread-safe on its own,
            // but the adapter's playlist/crossfade state still assumes a single writer.
            // UI listener notifications are dispatched to Dispatchers.Main separately.
            val playerDispatcher = Executors.newSingleThreadExecutor { r ->
                Thread(r, "Desktop-Player-Thread").apply { isDaemon = true }
            }.asCoroutineDispatcher()
            CoroutineScope(playerDispatcher + SupervisorJob())
        }

        single<MpvPlayerAdapter> {
            MpvPlayerAdapter(
                coroutineScope = get(named(SERVICE_SCOPE)),
                dataStoreManager = get(),
                streamRepository = get(),
            )
        }

        // ---- Active playback backend ----
        single<MediaPlayerInterface> {
            get<MpvPlayerAdapter>()
        }

        single<CacheRepository> {
            object : CacheRepository {
                override suspend fun getCacheSize(cacheName: String): Long = 0L

                override fun clearCache(cacheName: String) {}

                override suspend fun getAllCacheKeys(cacheName: String): List<String> = emptyList()
            }
        }
        single<DownloadHandler> {
            DownloadUtils(
                dataStoreManager = get(),
                streamRepository = get(),
                songRepository = get(),
            )
        }
    }

fun loadDesktopPlayerModule() = loadKoinModules(desktopPlayerModule)
