package com.maxrave.domain.repository

import com.maxrave.domain.data.entities.AutoEqEntryEntity
import com.maxrave.domain.data.model.autoeq.AutoEqCurve

interface AutoEqRepository {
    /** Cached profiles whose name matches [query]; a blank query returns the first [limit] rows. */
    suspend fun search(
        query: String,
        limit: Int = 60,
    ): List<AutoEqEntryEntity>

    /** How many profiles are cached. Zero means the index has never been downloaded. */
    suspend fun cachedCount(): Int

    /**
     * Bring the cached index up to date, downloading only when it has actually changed.
     *
     * Does nothing while the cache is inside its time-to-live unless [force]. Returns true when
     * rows were replaced, so a caller showing the old list knows to read it again.
     */
    suspend fun refreshIndex(force: Boolean = false): Boolean

    /**
     * Paths whose curve is already stored, so the picker can say which rows work with no
     * connection. Only headphones actually picked before are in here, so the set stays small.
     */
    suspend fun cachedCurvePaths(): Set<String>

    /** Fetch and parse one profile's curve, or null if it cannot be read. */
    suspend fun loadCurve(entry: AutoEqEntryEntity): AutoEqCurve?
}
