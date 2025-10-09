package com.maxrave.data.io

import okio.FileSystem


actual fun fileSystem(): FileSystem {
    return FileSystem.SYSTEM
}