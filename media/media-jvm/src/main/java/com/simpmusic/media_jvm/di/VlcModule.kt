package com.simpmusic.media_jvm.di

import com.maxrave.common.Config.SERVICE_SCOPE
import com.maxrave.domain.mediaservice.handler.DownloadHandler
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.repository.CacheRepository
import com.simpmusic.media_jvm.VlcPlayerAdapter
import com.simpmusic.media_jvm.download.DownloadUtils
import com.simpmusic.media_jvm.mpv.MpvPlayerAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import java.util.concurrent.Executors
import org.koin.core.context.loadKoinModules
import org.koin.core.qualifier.named
import org.koin.dsl.module

private val vlcModule =
    module {
        single<CoroutineScope>(qualifier = named(SERVICE_SCOPE)) {
            // Single-thread dispatcher: serializes all player operations onto one thread so the
            // adapter's state machine never races with itself. (Required outright for VLC, whose
            // native calls are not thread-safe; libmpv is thread-safe, but the adapter's own
            // playlist/crossfade state still assumes a single writer.)
            // UI listener notifications are dispatched to Dispatchers.Main separately.
            val playerDispatcher = Executors.newSingleThreadExecutor { r ->
                Thread(r, "Desktop-Player-Thread").apply { isDaemon = true }
            }.asCoroutineDispatcher()
            CoroutineScope(playerDispatcher + SupervisorJob())
        }

        // Kept registered (not deleted) so the VLC backend stays available for side-by-side
        // comparison while the mpv backend is evaluated. Nothing injects this type any more, and
        // the definition is lazy (no createdAtStart), so VLC is never actually constructed at
        // runtime — no MediaPlayerFactory, no native discovery. Re-binding the two
        // MediaPlayerInterface lines below is all it takes to bring it back.
        single<VlcPlayerAdapter> {
            VlcPlayerAdapter(
                coroutineScope = get(named(SERVICE_SCOPE)),
                dataStoreManager = get(),
                streamRepository = get(),
            )
        }

        single<MpvPlayerAdapter> {
            MpvPlayerAdapter(
                coroutineScope = get(named(SERVICE_SCOPE)),
                dataStoreManager = get(),
                streamRepository = get(),
            )
        }

        // ---- Active playback backend ----
        // mpv is the backend in use. To switch back to VLC, swap the two bindings below.
        //
        // single<MediaPlayerInterface> {
        //     get<VlcPlayerAdapter>()
        // }
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

fun loadVlcModule() = loadKoinModules(vlcModule)