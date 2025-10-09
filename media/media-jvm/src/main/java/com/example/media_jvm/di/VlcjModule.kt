package com.example.media_jvm.di

import com.maxrave.common.Config.SERVICE_SCOPE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.context.loadKoinModules
import org.koin.core.qualifier.named
import org.koin.dsl.module
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer

private val vlcjModule = module {
    single<CoroutineScope>(qualifier = named(SERVICE_SCOPE)) {
        CoroutineScope(Dispatchers.Main + SupervisorJob())
    }
    single<MediaPlayer> {
        val factory = MediaPlayerFactory()
        factory.mediaPlayers().newMediaPlayer()
    }
}

fun loadVlcjModule() = loadKoinModules(vlcjModule)