package com.maxrave.data.librarysync

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * The settings that travel between paired devices, and how. Only what describes the listener —
 * lyrics, looks, content choices — never what belongs to the device or is secret: sign-ins and
 * tokens, AI keys, proxy, audio quality, the equalizer and every effect, playback speed, the lyrics
 * offset (it follows the listener's Bluetooth path), backups, caches and playback state.
 *
 * Settings are sent every round but never applied by themselves: a difference becomes
 * [SyncPrefsState.pendingSettings] and the user decides, then the app restarts.
 */
internal class SyncedSettings(
    private val settings: DataStore<Preferences>,
    private val prefs: SyncPrefs,
) {
    /** Whether this device keeps listening history; off, it neither sends nor takes plays. */
    suspend fun localTracking(): Boolean = settings.data.first()[stringPreferencesKey("local_tracking_enabled")] == "TRUE"

    /** This device's allowlisted settings, with when they last changed as far as sync has seen. */
    suspend fun snapshot(): SettingsWire {
        val values =
            settings.data
                .first()
                .asMap()
                .mapNotNull { (key, value) -> if (key.name in KEYS) encode(value)?.let { key.name to it } else null }
                .toMap()
        val hash = values.hashCode()
        val state = prefs.current()
        val changedAt =
            if (state.settingsHash == hash) {
                state.settingsChangedAt
            } else {
                // Noticed now, so stamped now: close enough for "which device changed last".
                prefs.update { it.copy(settingsHash = hash, settingsChangedAt = nowMillis()) }.settingsChangedAt
            }
        return SettingsWire(changedAt, values)
    }

    /** The other device's settings arrived; keep them for the user if they differ and are newer. */
    suspend fun receive(
        remote: SettingsWire,
        fromName: String,
    ) {
        val local = snapshot()
        val state = prefs.current()
        val differs = remote.values.any { (name, value) -> local.values[name] != value }
        when {
            !differs -> if (state.pendingSettings != null) prefs.update { it.copy(pendingSettings = null, pendingSettingsFrom = null) }
            remote.changedAt <= local.changedAt || remote.changedAt <= state.dismissedSettingsAt -> Unit
            // Only what differs is kept, so the prompt names exactly what applying would change.
            else -> {
                val differing = remote.copy(values = remote.values.filter { (name, value) -> local.values[name] != value })
                prefs.update { it.copy(pendingSettings = differing, pendingSettingsFrom = fromName) }
            }
        }
    }

    suspend fun applyPending() {
        val pending = prefs.current().pendingSettings ?: return
        settings.edit { store ->
            // A value is written only as the type this device already keeps it as: one stored under
            // the wrong type (another app version, a tampered device) would crash every read of it.
            val current = store.asMap().mapKeys { it.key.name }
            pending.values.forEach { (name, setting) ->
                val mine = current[name]?.let { encode(it) }
                if (name in KEYS && (mine == null || mine.type == setting.type)) write(store, name, setting)
            }
        }
        // Stamped with the other device's time, so this side does not now look newer and send them back.
        val hash = snapshot().values.hashCode()
        prefs.update {
            it.copy(pendingSettings = null, pendingSettingsFrom = null, settingsHash = hash, settingsChangedAt = pending.changedAt)
        }
    }

    suspend fun dismissPending() {
        prefs.update {
            it.copy(dismissedSettingsAt = it.pendingSettings?.changedAt ?: it.dismissedSettingsAt, pendingSettings = null, pendingSettingsFrom = null)
        }
    }

    private fun encode(value: Any): SettingWire? =
        when (value) {
            is String -> SettingWire("s", value)
            is Boolean -> SettingWire("b", value.toString())
            is Int -> SettingWire("i", value.toString())
            is Long -> SettingWire("l", value.toString())
            is Float -> SettingWire("f", value.toString())
            is Double -> SettingWire("d", value.toString())
            is Set<*> -> SettingWire("ss", syncJson.encodeToString(ListSerializer(String.serializer()), value.filterIsInstance<String>()))
            else -> null
        }

    private fun write(
        store: MutablePreferences,
        name: String,
        setting: SettingWire,
    ) {
        val text = setting.value
        when (setting.type) {
            "s" -> store[stringPreferencesKey(name)] = text
            "b" -> text.toBooleanStrictOrNull()?.let { store[booleanPreferencesKey(name)] = it }
            "i" -> text.toIntOrNull()?.let { store[intPreferencesKey(name)] = it }
            "l" -> text.toLongOrNull()?.let { store[longPreferencesKey(name)] = it }
            "f" -> text.toFloatOrNull()?.let { store[floatPreferencesKey(name)] = it }
            "d" -> text.toDoubleOrNull()?.let { store[doublePreferencesKey(name)] = it }
            "ss" ->
                runCatching { syncJson.decodeFromString(ListSerializer(String.serializer()), text) }
                    .getOrNull()
                    ?.let { store[stringSetPreferencesKey(name)] = it.toSet() }
        }
    }

    companion object {
        /** Key names in DataStoreManagerImpl. Adding one here makes it travel; think twice. */
        val KEYS =
            setOf(
                // Lyrics.
                "lyrics_provider",
                "translation_language",
                "use_translation_language",
                "use_ai_translation",
                "romanization_languages",
                "spotify_lyrics",
                "lyrics_style",
                "youtube_subtitle_language",
                "preferred_audio_language",
                "help_build_lyrics_database",
                // Looks.
                "theme_mode",
                "theme_color_source",
                "custom_theme_color",
                "now_playing_style",
                "home_limit",
                "chart_key",
                // Content.
                "explicit_content_enabled",
                "location",
                "radio_audio_only",
                "endless_queue",
                "sponsor_block_enabled",
                "spotify_canvas",
                "am_animated_artwork",
                "keep_youtube_playlist_offline",
                "combine_local_and_youtube_liked",
                "sync_follow_to_youtube",
                "local_tracking_enabled",
                "ai_provider",
                "custom_model_id",
            )
    }
}
