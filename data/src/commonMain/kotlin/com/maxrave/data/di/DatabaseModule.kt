package com.maxrave.data.di

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.maxrave.data.dataStore.DataStoreManagerImpl
import com.maxrave.data.dataStore.createDataStoreInstance
import com.maxrave.data.db.DatabaseDao
import com.maxrave.data.db.LocalDataSource
import com.maxrave.data.db.MusicDatabase
import com.maxrave.data.db.getDatabaseBuilder
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.spotify.Spotify
import org.koin.dsl.module
import org.simpmusic.aiservice.AiClient
import org.simpmusic.lyrics.SimpMusicLyricsClient
import kotlin.time.ExperimentalTime

@OptIn(ExperimentalTime::class)
val databaseModule =
    module {
        // Database
        single(createdAtStart = true) {
            getDatabaseBuilder().build()
        }
        // DatabaseDao
        single(createdAtStart = true) {
            get<MusicDatabase>().getDatabaseDao()
        }
        // LocalDataSource
        single(createdAtStart = true) {
            LocalDataSource(get<DatabaseDao>())
        }
        // Datastore
        single(createdAtStart = true) {
            createDataStoreInstance()
        }
        // DatastoreManager
        single<DataStoreManager>(createdAtStart = true) {
            DataStoreManagerImpl(get<DataStore<Preferences>>())
        }

        // Move YouTube from Singleton to Koin DI
        single(createdAtStart = true) {
            YouTube()
        }

        single(createdAtStart = true) {
            Spotify()
        }

        single(createdAtStart = true) {
            AiClient()
        }

        single(createdAtStart = true) {
            SimpMusicLyricsClient()
        }
    }