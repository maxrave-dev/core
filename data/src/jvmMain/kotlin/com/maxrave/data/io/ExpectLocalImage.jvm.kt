package com.maxrave.data.io

import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI

actual suspend fun readLocalImageBytes(uri: String): ByteArray? =
    withContext(Dispatchers.IO) {
        runCatching {
            // The desktop picker hands back a plain path in some places and a file: uri in others,
            // so both are accepted rather than assuming one shape.
            val file = if (uri.startsWith("file:")) File(URI(uri)) else File(uri)
            if (file.isFile) file.readBytes() else null
        }.onFailure {
            Logger.w("LocalImage", "Could not read $uri: ${it.message}")
        }.getOrNull()
    }
