package com.example.media_jvm.di

import com.example.media_jvm.VlcjPlayerAdapter
import com.maxrave.common.Config.SERVICE_SCOPE
import com.maxrave.domain.mediaservice.player.MediaPlayerInterface
import com.maxrave.domain.repository.CacheRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.context.loadKoinModules
import org.koin.core.qualifier.named
import org.koin.dsl.module
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer

private val vlcjModule =
    module {
        single<CoroutineScope>(qualifier = named(SERVICE_SCOPE)) {
            CoroutineScope(Dispatchers.Main + SupervisorJob())
        }
        single<MediaPlayer> {
            val factory = MediaPlayerFactory()
            factory.mediaPlayers().newMediaPlayer()
        }
        single<MediaPlayerInterface> {
            VlcjPlayerAdapter(get())
        }
        single<CacheRepository> {
            object : CacheRepository {
                override suspend fun getCacheSize(cacheName: String): Long = 0L

                override fun clearCache(cacheName: String) {}

                override suspend fun getAllCacheKeys(cacheName: String): List<String> = emptyList()
            }
        }
    }

fun loadVlcjModule() = loadKoinModules(vlcjModule)