package com.maxrave.domain.data.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One AutoEq correction profile, as listed in that project's own `results/INDEX.md`.
 *
 * The whole index is kept as rows rather than as the downloaded file, so looking a headphone up is
 * a `LIKE` against an indexed column instead of re-parsing eight hundred kilobytes of Markdown on
 * every search.
 */
@Entity(tableName = "autoeq_entry", indices = [Index(value = ["name"])])
data class AutoEqEntryEntity(
    /**
     * Folder path under `results/`, still URL-encoded, taken verbatim from the index.
     *
     * Stored rather than rebuilt from [source] and the rig, because the directory in between is
     * `"<rig> <form>"` for sources that name a rig and just `"<form>"` for those that do not —
     * a rule with no purpose here other than to be got wrong.
     */
    @PrimaryKey(autoGenerate = false)
    val path: String,
    val name: String,
    val source: String,
    val rig: String? = null,
)

/**
 * Freshness of the cached index. Exactly one row.
 *
 * It lives in the database, and is written in the same transaction as the rows themselves, so the
 * two can never disagree. Kept in the preference store instead, an [etag] surviving a wipe of the
 * table would claim the cache was current while there was nothing left in it to search.
 */
@Entity(tableName = "autoeq_index_meta")
data class AutoEqIndexMetaEntity(
    @PrimaryKey(autoGenerate = false)
    val id: Int = SINGLETON_ID,
    /** Whatever the last successful download answered with, replayed as `If-None-Match`. */
    val etag: String?,
    /** Epoch millis of that download, used only to decide whether to ask again at all. */
    val fetchedAt: Long,
) {
    companion object {
        const val SINGLETON_ID = 0
    }
}

/**
 * A profile's curve, kept once it has been downloaded.
 *
 * The index alone lets someone browse every headphone and then fail at the last step, because the
 * gains themselves live in a separate file per profile. Caching them means a headphone used before
 * can be selected again with no connection at all.
 *
 * [bandsDb] is comma-joined rather than a typed list, matching how the equalizer's own curve is
 * stored, so no type converter has to exist for one table.
 */
@Entity(tableName = "autoeq_curve")
data class AutoEqCurveEntity(
    @PrimaryKey(autoGenerate = false)
    val path: String,
    val bandsDb: String,
    val preampDb: Float,
)
