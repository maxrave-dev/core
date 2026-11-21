package com.maxrave.kotlinytmusicscraper.cipher

/**
 * Snapshot of a remote player config table persisted across app launches.
 */
data class StoredPlayerConfig(
    val sourceUrl: String,
    val json: String,
    val etag: String,
    val fetchedAtMs: Long,
)

/**
 * Optional persistence backend for [FaradayCipherEngine].
 *
 * Persisting `player_configs.json` (with its ETag/timestamp) and `base.js` per player hash avoids
 * re-downloading ~2.7 MB from GitHub and YouTube on every cold start, letting QuickJS compile
 * locally in milliseconds.
 */
interface FaradayCacheStore {
    fun readConfig(key: String): StoredPlayerConfig?

    fun writeConfig(
        key: String,
        config: StoredPlayerConfig,
    )

    fun readPlayerScript(playerId: String): String?

    fun writePlayerScript(
        playerId: String,
        script: String,
    )

    fun deletePlayerScript(playerId: String)
}

/**
 * [PlayerConfigRepository] backed by [FaradayCacheStore] when available, falling back cleanly to
 * in-memory state otherwise.
 */
internal class PersistedPlayerConfigRepository(
    url: String,
    private val storeKey: String,
    private val store: FaradayCacheStore?,
) : PlayerConfigRepository {
    override val enabled: Boolean = true
    override val sourceUrl: String = url
    override val defaultSourceUrl: String = url

    private var hydrated: Boolean = false
    private var _cachedJson: String = ""
    private var _cachedAtMs: Long = 0L
    private var _cachedSourceUrl: String = ""
    private var _cachedEtag: String = ""

    private fun ensureHydrated() {
        if (hydrated) return
        hydrated = true
        val stored = store?.readConfig(storeKey) ?: return
        _cachedSourceUrl = stored.sourceUrl
        _cachedJson = stored.json
        _cachedEtag = stored.etag
        _cachedAtMs = stored.fetchedAtMs
    }

    override var cachedJson: String
        get() {
            ensureHydrated()
            return _cachedJson
        }
        set(value) {
            hydrated = true
            _cachedJson = value
        }

    override var cachedAtMs: Long
        get() {
            ensureHydrated()
            return _cachedAtMs
        }
        set(value) {
            hydrated = true
            _cachedAtMs = value
        }

    override var cachedSourceUrl: String
        get() {
            ensureHydrated()
            return _cachedSourceUrl
        }
        set(value) {
            hydrated = true
            _cachedSourceUrl = value
        }

    override var cachedEtag: String
        get() {
            ensureHydrated()
            return _cachedEtag
        }
        set(value) {
            hydrated = true
            _cachedEtag = value
        }

    override fun onCacheUpdated() {
        if (store == null || _cachedJson.isBlank()) return
        store.writeConfig(
            storeKey,
            StoredPlayerConfig(
                sourceUrl = _cachedSourceUrl,
                json = _cachedJson,
                etag = _cachedEtag,
                fetchedAtMs = _cachedAtMs,
            ),
        )
    }
}
