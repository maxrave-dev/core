package com.maxrave.media3.repository

import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.SimpleCache
import com.maxrave.common.Config
import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.repository.CacheRepository
import com.maxrave.media3.extension.isFullyCached
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@UnstableApi
internal class CacheRepositoryImpl(
    private val playerCache: SimpleCache,
    private val downloadCache: SimpleCache,
    private val canvasCache: SimpleCache,
) : CacheRepository {
    override suspend fun getCacheSize(cacheName: String): Long =
        when (cacheName) {
            Config.PLAYER_CACHE -> {
                playerCache.cacheSpace
            }
            Config.DOWNLOAD_CACHE -> {
                downloadCache.cacheSpace
            }
            Config.CANVAS_CACHE -> {
                canvasCache.cacheSpace
            }
            else -> 0L
        }

    override fun clearCache(cacheName: String) {
        when (cacheName) {
            Config.PLAYER_CACHE -> {
                playerCache.keys.forEach { key ->
                    playerCache.removeResource(key)
                }
            }
            Config.DOWNLOAD_CACHE -> {
                downloadCache.keys.forEach { key ->
                    downloadCache.removeResource(key)
                }
            }
            Config.CANVAS_CACHE -> {
                canvasCache.keys.forEach { key ->
                    canvasCache.removeResource(key)
                }
            }
            else -> { /* no-op */ }
        }
    }

    override suspend fun getAllCacheKeys(cacheName: String): List<String> =
        when (cacheName) {
            Config.PLAYER_CACHE -> {
                playerCache.keys.toList()
            }
            Config.DOWNLOAD_CACHE -> {
                downloadCache.keys.toList()
            }
            Config.CANVAS_CACHE -> {
                canvasCache.keys.toList()
            }
            else -> emptyList()
        }

    // SimpleCache is synchronized, and a write in progress holds its lock, so these stay off the
    // caller's thread. The video stream of a song is cached under its own prefixed key.
    override suspend fun getFullyCachedSongIds(): List<String> =
        withContext(Dispatchers.IO) {
            playerCache.keys.filter { key ->
                !key.startsWith(MERGING_DATA_TYPE.VIDEO) && playerCache.isFullyCached(key, 0L)
            }
        }

    override suspend fun removeCachedSong(videoId: String) =
        withContext(Dispatchers.IO) {
            playerCache.removeResource(videoId)
            playerCache.removeResource(MERGING_DATA_TYPE.VIDEO + videoId)
        }
}