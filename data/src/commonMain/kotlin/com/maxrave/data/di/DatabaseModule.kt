package com.maxrave.data.di

import DatabaseDao
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.maxrave.data.dataStore.DataStoreManagerImpl
import com.maxrave.data.dataStore.createDataStoreInstance
import com.maxrave.data.db.Converters
import com.maxrave.data.db.MusicDatabase
import com.maxrave.data.db.datasource.AnalyticsDatasource
import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.data.db.getDatabaseBuilder
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.kotlinytmusicscraper.YouTube
import com.maxrave.spotify.Spotify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import org.koin.dsl.module
import org.simpmusic.aiservice.AiClient
import org.simpmusic.lyrics.SimpMusicLyricsClient
import kotlin.time.ExperimentalTime
import org.simpmusic.autoeq.AutoEq

@OptIn(ExperimentalTime::class)
val databaseModule =
    module {
        single(createdAtStart = true) {
            Converters()
        }
        // Database
        single(createdAtStart = true) {
            getDatabaseBuilder(
                get<Converters>()
            )
                .setDriver(BundledSQLiteDriver())
                .setQueryCoroutineContext(Dispatchers.IO)
                .build()
        }
        // DatabaseDao
        single(createdAtStart = true) {
            get<MusicDatabase>().getDatabaseDao()
        }
        // LocalDataSource
        single(createdAtStart = true) {
            LocalDataSource(get<DatabaseDao>(), get<MusicDatabase>())
        }
        // AnalyticsDatasource
        single(createdAtStart = true) {
            AnalyticsDatasource(get<DatabaseDao>())
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

        // Not created at start, unlike the rest: nothing needs it until someone opens the AutoEq
        // picker, and it holds an HTTP client the vast majority of sessions never use.
        single {
            AutoEq()
        }
    }