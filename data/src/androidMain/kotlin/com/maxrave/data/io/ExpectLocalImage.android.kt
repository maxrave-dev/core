package com.maxrave.data.io

import android.content.Context
import android.net.Uri
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.mp.KoinPlatform.getKoin

actual suspend fun readLocalImageBytes(uri: String): ByteArray? =
    withContext(Dispatchers.IO) {
        runCatching {
            val context = getKoin().get<Context>()
            context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.readBytes() }
        }.onFailure {
            Logger.w("LocalImage", "Could not read $uri: ${it.message}")
        }.getOrNull()
    }
