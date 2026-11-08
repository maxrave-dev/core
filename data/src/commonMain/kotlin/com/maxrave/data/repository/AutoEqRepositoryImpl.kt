package com.maxrave.data.repository

import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.domain.data.entities.AutoEqCurveEntity
import com.maxrave.domain.data.entities.AutoEqEntryEntity
import com.maxrave.domain.data.entities.AutoEqIndexMetaEntity
import com.maxrave.domain.data.model.autoeq.AutoEqCurve
import com.maxrave.domain.repository.AutoEqRepository
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.simpmusic.autoeq.AutoEq
import org.simpmusic.autoeq.AutoEqIndexResult
import kotlin.time.Clock

private const val TAG = "AutoEqRepositoryImpl"

/** How long a downloaded index is trusted before the app will even ask whether it changed. */
private const val INDEX_TTL_MS = 7L * 24 * 60 * 60 * 1000

internal class AutoEqRepositoryImpl(
    private val localDataSource: LocalDataSource,
    private val autoEq: AutoEq,
) : AutoEqRepository {
    override suspend fun search(
        query: String,
        limit: Int,
    ): List<AutoEqEntryEntity> =
        withContext(Dispatchers.IO) {
            localDataSource.searchAutoEqEntries(query.trim(), limit)
        }

    override suspend fun cachedCount(): Int =
        withContext(Dispatchers.IO) {
            localDataSource.getAutoEqEntryCount()
        }

    override suspend fun refreshIndex(force: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val meta = localDataSource.getAutoEqIndexMeta()
            val cached = localDataSource.getAutoEqEntryCount()
            val now = Clock.System.now().toEpochMilliseconds()
            val withinTtl = meta != null && cached > 0 && now - meta.fetchedAt < INDEX_TTL_MS
            if (!force && withinTtl) return@withContext false

            // The stored ETag is only replayed while there are rows it could be describing. An
            // empty table with a surviving ETag would otherwise earn a 304 and stay empty forever.
            val etag = meta?.etag?.takeIf { cached > 0 }
            when (val result = autoEq.fetchIndex(etag)) {
                is AutoEqIndexResult.NotModified -> {
                    localDataSource.updateAutoEqIndexMeta(
                        AutoEqIndexMetaEntity(etag = meta?.etag, fetchedAt = now),
                    )
                    false
                }

                is AutoEqIndexResult.Updated -> {
                    localDataSource.replaceAutoEqIndex(
                        entries =
                            result.entries.map {
                                AutoEqEntryEntity(
                                    path = it.path,
                                    name = it.name,
                                    source = it.source,
                                    rig = it.rig,
                                )
                            },
                        meta = AutoEqIndexMetaEntity(etag = result.etag, fetchedAt = now),
                    )
                    Logger.d(TAG, "index updated: ${result.entries.size} profiles")
                    true
                }

                is AutoEqIndexResult.Failed -> {
                    // Deliberately leaves both the rows and the old fetchedAt alone: a failed check
                    // must not read as a successful one, or a network blip would buy silence for a
                    // week. Whatever is cached stays usable in the meantime.
                    Logger.e(TAG, "index refresh failed: ${result.reason}")
                    false
                }
            }
        }

    override suspend fun cachedCurvePaths(): Set<String> =
        withContext(Dispatchers.IO) {
            localDataSource.getAutoEqCachedCurvePaths().toSet()
        }

    /**
     * The curve for [entry], from the cache when it has been fetched before.
     *
     * The index on its own lets someone browse every headphone offline and then fail on the last
     * tap, because the gains live in a file of their own per profile. Caching each one as it is
     * downloaded means a headphone already used can be picked again with no connection.
     */
    override suspend fun loadCurve(entry: AutoEqEntryEntity): AutoEqCurve? =
        withContext(Dispatchers.IO) {
            localDataSource.getAutoEqCurve(entry.path)?.let { cached ->
                val bands = cached.bandsDb.split(",").mapNotNull { it.trim().toFloatOrNull() }
                if (bands.isNotEmpty()) return@withContext AutoEqCurve(bands, cached.preampDb)
            }
            val fetched = autoEq.fetchFixedBandCurve(entry.path) ?: return@withContext null
            localDataSource.insertAutoEqCurve(
                AutoEqCurveEntity(
                    path = entry.path,
                    bandsDb = fetched.bandsDb.joinToString(","),
                    preampDb = fetched.preampDb,
                ),
            )
            AutoEqCurve(bandsDb = fetched.bandsDb, preampDb = fetched.preampDb)
        }
}
