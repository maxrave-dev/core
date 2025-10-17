package com.simpmusic.media_jvm.di

import com.maxrave.common.Config.MAIN_PLAYER
import com.maxrave.common.Config.SERVICE_SCOPE
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.repository.CacheRepository
import com.simpmusic.media_jvm.GstreamerPlayerAdapter
import com.simpmusic.media_jvm.download.DownloadUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import org.freedesktop.gstreamer.elements.AppSink
import org.freedesktop.gstreamer.swing.GstVideoComponent
import org.koin.core.context.loadKoinModules
import org.koin.core.qualifier.named
import org.koin.dsl.module

private val gstreamerModule =
    module {
        single<CoroutineScope>(qualifier = named(SERVICE_SCOPE)) {
            CoroutineScope(Dispatchers.Main + SupervisorJob())
        }
        single<Deferred<GstVideoComponent>>(named(MAIN_PLAYER)) {
            GlobalScope.async {
                withContext(Dispatchers.Swing) {
                    GstVideoComponent(
                        AppSink(MAIN_PLAYER),
                    )
                }
            }
        }
        single<MediaPlayerInterface> {
            GstreamerPlayerAdapter(
                videoComponent = get(named(MAIN_PLAYER)),
                coroutineScope = get(named(SERVICE_SCOPE)),
                dataStoreManager = get(),
                streamRepository = get(),
            )
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

fun loadGstreamerModule() = loadKoinModules(gstreamerModule)