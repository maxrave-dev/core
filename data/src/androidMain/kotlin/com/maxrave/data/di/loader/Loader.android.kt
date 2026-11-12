package com.maxrave.data.di.loader

import com.maxrave.media3.di.loadMediaService
import com.maxrave.data.librarysync.LibrarySyncPhoneRepositoryImpl
import com.maxrave.data.librarysync.SyncApplier
import com.maxrave.data.librarysync.SyncEngine
import com.maxrave.data.librarysync.SyncedSettings
import com.maxrave.data.loginsync.LoginSyncSenderRepositoryImpl
import com.maxrave.data.loginsync.LoginSyncStore
import com.maxrave.domain.repository.LibrarySyncPhoneRepository
import com.maxrave.domain.repository.LibrarySyncRepository
import com.maxrave.domain.repository.LoginSyncSenderRepository
import org.koin.core.context.loadKoinModules
import org.simpmusic.loginsync.LoginSyncClient
import org.simpmusic.loginsync.PairedSyncClient
import org.koin.dsl.module

actual fun loadMediaService() {
    loadMediaService()
}

actual fun loadLoginSyncModule() {
    loadKoinModules(
        module {
            single { LoginSyncStore(get(), get(), get()) }
            single<LoginSyncSenderRepository> { LoginSyncSenderRepositoryImpl(get(), LoginSyncClient()) }
            // Library sync (#2663): this phone drives every round with the computer it is paired with.
            single { SyncedSettings(get(), get()) }
            single { SyncEngine(get(), get(), SyncApplier(get(), get()), get()) }
            single<LibrarySyncPhoneRepository> {
                LibrarySyncPhoneRepositoryImpl(get(), get(), get(), get(), get(), get(), get(), PairedSyncClient())
            }
            single<LibrarySyncRepository> { get<LibrarySyncPhoneRepository>() }
        },
    )
}
