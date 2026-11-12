package com.maxrave.data.librarysync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val TAG = "LibrarySync"

/**
 * Library sync's own small file, `library_sync.preferences_pb`, beside the settings file but NOT in
 * it: the app's backup copies the settings file whole, and a pairing key restored onto another
 * device would let that device pose as this one. Deleting this file is the same as unpairing.
 */
expect fun createLibrarySyncDataStore(): DataStore<Preferences>

/** Where the database copies taken before a merge that deletes something are kept. */
expect fun syncBackupDirectory(): String

/** The computer, as the phone remembers it after pairing. */
@Serializable
internal data class PairedDesktop(
    val desktopId: String,
    val name: String,
    val os: String,
    val keyHex: String,
    val port: Int,
    /** The address the user picked when pairing: LAN, Tailscale, anything else the computer listed. */
    val preferredHost: String,
    /** Every address the computer last reported; IPs are not static, so this is refreshed each round. */
    val hosts: List<String>,
)

/** A phone, as the computer remembers it. */
@Serializable
internal data class PairedPhone(
    val phoneId: String,
    val name: String,
    val os: String,
    val keyHex: String,
    val since: Long,
    val lastSyncAt: Long? = null,
)

@Serializable
internal data class SyncPrefsState(
    val deviceId: String? = null,
    // Phone.
    val desktop: PairedDesktop? = null,
    val lastServerSeq: Long = 0,
    // Computer.
    val phones: List<PairedPhone> = emptyList(),
    /** Phones removed while away: kept a while so they can be told, under their own key, that they are out. */
    val revoked: List<PairedPhone> = emptyList(),
    // Both.
    val lastSyncAt: Long? = null,
    val lastBackupAt: Long = 0,
    val settingsHash: Int? = null,
    val settingsChangedAt: Long = 0,
    val pendingSettings: SettingsWire? = null,
    val pendingSettingsFrom: String? = null,
    val dismissedSettingsAt: Long = 0,
) {
    val paired: Boolean get() = desktop != null || phones.isNotEmpty()
}

internal class SyncPrefs(
    private val store: DataStore<Preferences>,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loaded = CompletableDeferred<Unit>()
    private val _state = MutableStateFlow(SyncPrefsState())
    val state: StateFlow<SyncPrefsState> = _state.asStateFlow()

    init {
        scope.launch {
            try {
                store.data.collect { prefs ->
                    _state.value = decode(prefs[KEY])
                    loaded.complete(Unit)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Unreadable even after the corruption handler: run unpaired rather than leave every
                // synced write waiting on a load that never comes.
                Logger.e(TAG, "library_sync state unreadable", e)
                loaded.complete(Unit)
            }
        }
    }

    suspend fun current(): SyncPrefsState {
        loaded.await()
        return _state.value
    }

    suspend fun update(transform: (SyncPrefsState) -> SyncPrefsState): SyncPrefsState {
        loaded.await()
        var next = _state.value
        store.edit { prefs ->
            next = transform(decode(prefs[KEY]))
            prefs[KEY] = json.encodeToString(SyncPrefsState.serializer(), next)
        }
        _state.value = next
        return next
    }

    /** This install's id: made once, kept across unpairing, gone with the app's data. */
    suspend fun deviceId(): String =
        current().deviceId ?: update { if (it.deviceId != null) it else it.copy(deviceId = newSyncId()) }.deviceId!!

    private fun decode(raw: String?): SyncPrefsState =
        raw?.let {
            runCatching { json.decodeFromString(SyncPrefsState.serializer(), it) }
                .onFailure { e -> Logger.e(TAG, "library_sync state unreadable, starting unpaired", e) }
                .getOrNull()
        } ?: SyncPrefsState()

    private companion object {
        val KEY = stringPreferencesKey("state")
        val json = Json { ignoreUnknownKeys = true }
    }
}
