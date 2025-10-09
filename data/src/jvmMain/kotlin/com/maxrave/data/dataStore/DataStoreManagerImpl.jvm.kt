package com.maxrave.data.dataStore

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import com.maxrave.common.SETTINGS_FILENAME
import createDataStore
import java.io.File

actual fun createDataStoreInstance(): DataStore<Preferences> = createDataStore(
    producePath = {
        val file = File(System.getProperty("java.io.tmpdir"), SETTINGS_FILENAME)
        file.absolutePath
    }
)