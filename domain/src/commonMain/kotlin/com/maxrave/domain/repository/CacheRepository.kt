package com.maxrave.domain.repository

interface CacheRepository {
    suspend fun getCacheSize(cacheName: String): Long

    fun clearCache(cacheName: String)

    suspend fun getAllCacheKeys(cacheName: String): List<String>

    /**
     * Songs whose audio sits in the player cache in full, so they play with no network. A track
     * skipped halfway is left out. Always empty where there is no on-disk player cache (Desktop).
     */
    suspend fun getFullyCachedSongIds(): List<String>

    /** Drops a song's cached audio, and its cached video if there is one, from the player cache. */
    suspend fun removeCachedSong(videoId: String)
}