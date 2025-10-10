package com.maxrave.data.io

import okio.FileSystem
import java.io.File


actual fun fileSystem(): FileSystem {
    return FileSystem.SYSTEM
}

actual fun fileDir(): String = File(System.getProperty("java.io.tmpdir"), "SimpMusic").absolutePath