package com.maxrave.media3.repository

import androidx.media3.datasource.cache.SimpleCache
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.repository.CachedSongsRepository
import com.maxrave.domain.repository.SongRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

class CachedSongsRepositoryImpl(
    private val playerCache: SimpleCache,
    private val songRepository: SongRepository
) : CachedSongsRepository {
    override fun getCachedSongs(): Flow<List<SongEntity>> {
        val keys = playerCache.keys
        val songIds = keys.map { key -> 
            if (key.contains("-_video")) key.removeSuffix("-_video") else key 
        }.distinct().toList()
        
        return if (songIds.isEmpty()) {
            flow { emit(emptyList()) }
        } else {
            songRepository.getSongsByListVideoId(songIds)
        }
    }
}
