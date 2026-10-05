package com.maxrave.data.di.loader

import android.os.Build
import android.provider.Settings
import com.maxrave.media3.di.loadMediaService
import com.maxrave.data.loginsync.LoginSyncHostRepositoryImpl
import com.maxrave.data.loginsync.LoginSyncSenderRepositoryImpl
import com.maxrave.data.loginsync.LoginSyncStore
import com.maxrave.domain.repository.LoginSyncHostRepository
import com.maxrave.domain.repository.LoginSyncSenderRepository
import org.koin.android.ext.koin.androidContext
import org.koin.core.context.loadKoinModules
import org.simpmusic.loginsync.DeviceName
import org.simpmusic.loginsync.LoginSyncClient
import org.koin.dsl.module

actual fun loadMediaService() {
    loadMediaService()
}

actual fun loadLoginSyncModule() {
    loadKoinModules(
        module {
            single { LoginSyncStore(get(), get(), get()) }
            single<LoginSyncSenderRepository> { LoginSyncSenderRepositoryImpl(get(), LoginSyncClient()) }
            // An Android TV receives, the way Desktop does: it has no camera to scan with.
            single<LoginSyncHostRepository> {
                val resolver = androidContext().contentResolver
                LoginSyncHostRepositoryImpl(get()) {
                    DeviceName(
                        // The name the user gave this TV in its own settings, else the model.
                        name = Settings.Global.getString(resolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL,
                        os = "Android ${Build.VERSION.RELEASE}",
                    )
                }
            }
        },
    )
}
