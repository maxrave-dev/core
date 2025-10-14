package com.maxrave.data.di.loader

import com.example.media_jvm.di.loadGstreamerModule

actual fun loadMediaService() {
    loadGstreamerModule()
}