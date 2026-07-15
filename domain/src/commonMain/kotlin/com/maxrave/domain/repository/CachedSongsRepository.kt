package com.maxrave.domain.repository

import com.maxrave.domain.data.entities.SongEntity
import kotlinx.coroutines.flow.Flow

interface CachedSongsRepository {
    fun getCachedSongs(): Flow<List<SongEntity>>
}
