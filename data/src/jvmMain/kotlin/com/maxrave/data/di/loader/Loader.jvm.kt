package com.maxrave.data.di.loader

import com.simpmusic.media_jvm.di.loadDesktopPlayerModule
import com.maxrave.data.browserlogin.BrowserLoginRepositoryImpl
import com.maxrave.data.librarysync.LibrarySyncHostRepositoryImpl
import com.maxrave.data.librarysync.SyncApplier
import com.maxrave.data.librarysync.SyncEngine
import com.maxrave.data.librarysync.SyncedSettings
import com.maxrave.data.loginsync.LoginSyncHostRepositoryImpl
import com.maxrave.data.loginsync.LoginSyncStore
import com.maxrave.domain.repository.LibrarySyncHostRepository
import com.maxrave.domain.repository.LibrarySyncRepository
import com.maxrave.domain.repository.LoginSyncHostRepository
import com.maxrave.domain.repository.BrowserLoginRepository
import org.koin.core.context.loadKoinModules
import org.koin.dsl.module

actual fun loadMediaService() {
    loadDesktopPlayerModule()
}

actual fun loadLoginSyncModule() {
    loadKoinModules(
        module {
            single { LoginSyncStore(get(), get(), get()) }
            single<LoginSyncHostRepository> { LoginSyncHostRepositoryImpl(get()) }
            // Library sync (#2663): this computer keeps the log and serves its paired phones.
            single { SyncedSettings(get(), get()) }
            single { SyncEngine(get(), get(), SyncApplier(get(), get()), get()) }
            single<LibrarySyncHostRepository> { LibrarySyncHostRepositoryImpl(get(), get(), get(), get(), get(), get()) }
            single<LibrarySyncRepository> { get<LibrarySyncHostRepository>() }
            single<BrowserLoginRepository> { BrowserLoginRepositoryImpl() }
        },
    )
}
