package com.maxrave.data.di.loader

import com.simpmusic.media_jvm.di.loadGstreamerModule

actual fun loadMediaService() {
    loadGstreamerModule()
}