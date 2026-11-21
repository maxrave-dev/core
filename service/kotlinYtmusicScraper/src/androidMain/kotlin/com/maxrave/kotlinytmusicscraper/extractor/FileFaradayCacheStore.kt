package com.maxrave.kotlinytmusicscraper.extractor

import android.content.Context
import com.maxrave.kotlinytmusicscraper.cipher.FaradayCacheStore
import com.maxrave.kotlinytmusicscraper.cipher.StoredPlayerConfig
import com.maxrave.logger.Logger
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.context.GlobalContext
import java.io.File

private const val TAG = "FileFaradayCacheStore"
private const val CACHE_DIR_NAME = "faraday_cipher_cache"
private const val MAX_CACHED_PLAYER_SCRIPTS = 2

@Serializable
private data class SerializedPlayerConfig(
    val sourceUrl: String,
    val json: String,
    val etag: String,
    val fetchedAtMs: Long,
)

internal class FileFaradayCacheStore(
    private val cacheDirProvider: () -> File? = {
        val androidCache =
            runCatching {
                GlobalContext.getOrNull()?.getOrNull<Context>()?.cacheDir
            }.getOrNull()
        val baseDir =
            androidCache
                ?: System.getProperty("java.io.tmpdir")?.takeIf { it.isNotBlank() }?.let(::File)
        baseDir?.let { File(it, CACHE_DIR_NAME) }
    },
) : FaradayCacheStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val ioLock = Any()

    override fun readConfig(key: String): StoredPlayerConfig? =
        synchronized(ioLock) {
            val file = configFile(key) ?: return null
            if (!file.isFile) return null
            return runCatching {
                val decoded = json.decodeFromString<SerializedPlayerConfig>(file.readText())
                StoredPlayerConfig(
                    sourceUrl = decoded.sourceUrl,
                    json = decoded.json,
                    etag = decoded.etag,
                    fetchedAtMs = decoded.fetchedAtMs,
                )
            }.onFailure { error ->
                Logger.w(TAG, "Failed to read Faraday config cache ${file.name}: ${error.message}")
            }.getOrNull()
        }

    override fun writeConfig(
        key: String,
        config: StoredPlayerConfig,
    ) {
        synchronized(ioLock) {
            val file = configFile(key) ?: return
            runCatching {
                val payload =
                    json.encodeToString(
                        SerializedPlayerConfig(
                            sourceUrl = config.sourceUrl,
                            json = config.json,
                            etag = config.etag,
                            fetchedAtMs = config.fetchedAtMs,
                        ),
                    )
                writeAtomic(file, payload)
            }.onFailure { error ->
                Logger.w(TAG, "Failed to write Faraday config cache ${file.name}: ${error.message}")
            }
        }
    }

    override fun readPlayerScript(playerId: String): String? =
        synchronized(ioLock) {
            val file = scriptFile(playerId) ?: return null
            if (!file.isFile) return null
            return runCatching {
                file.setLastModified(System.currentTimeMillis())
                file.readText()
            }.onFailure { error ->
                Logger.w(TAG, "Failed to read Faraday player script $playerId: ${error.message}")
            }.getOrNull()
        }

    override fun writePlayerScript(
        playerId: String,
        script: String,
    ) {
        synchronized(ioLock) {
            val file = scriptFile(playerId) ?: return
            runCatching {
                writeAtomic(file, script)
                pruneOldScripts(file.parentFile)
            }.onFailure { error ->
                Logger.w(TAG, "Failed to write Faraday player script $playerId: ${error.message}")
            }
        }
    }

    override fun deletePlayerScript(playerId: String) {
        synchronized(ioLock) {
            runCatching {
                scriptFile(playerId)?.delete()
            }
        }
    }

    private fun ensureCacheDir(): File? {
        val dir = cacheDirProvider() ?: return null
        if (!dir.exists() && !dir.mkdirs()) return null
        return dir
    }

    private fun configFile(key: String): File? {
        val dir = ensureCacheDir() ?: return null
        return File(dir, "config_${sanitizeKey(key)}.json")
    }

    private fun scriptFile(playerId: String): File? {
        val dir = ensureCacheDir() ?: return null
        return File(dir, "player_${sanitizeKey(playerId)}.js")
    }

    private fun sanitizeKey(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun writeAtomic(
        target: File,
        content: String,
    ) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(target)) {
            target.writeText(content)
            tmp.delete()
        }
    }

    private fun pruneOldScripts(dir: File?) {
        dir ?: return
        val scripts =
            dir
                .listFiles { file ->
                    file.isFile && file.name.startsWith("player_") && file.name.endsWith(".js")
                }?.sortedByDescending { it.lastModified() }
                ?: return
        if (scripts.size <= MAX_CACHED_PLAYER_SCRIPTS) return
        scripts.drop(MAX_CACHED_PLAYER_SCRIPTS).forEach { stale ->
            stale.delete()
        }
    }
}
