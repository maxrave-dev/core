package com.maxrave.domain.data.entities.sync

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One change to a synced library item, recorded by library sync (#2663).
 *
 * On a phone it is the outbox: [serverSeq] is null until the paired computer has taken it. On the
 * computer it is the log every phone pulls from, ordered by [serverSeq]; only the newest change per
 * ([entity], [entityKey]) is kept there, except for listening history, whose keys never repeat.
 *
 * It exists because unliking or unfollowing writes the item's timestamp back to null, so without a
 * record of its own the moment of an "off" would be lost and two devices could not tell which change
 * came last.
 */
@Entity(
    tableName = "sync_change",
    indices = [
        Index(value = ["serverSeq"]),
        Index(value = ["entity", "entityKey"]),
    ],
)
data class SyncChangeEntity(
    @PrimaryKey val changeId: String,
    val entity: String,
    val entityKey: String,
    val op: String,
    val value: String? = null,
    /** Epoch milliseconds of the change on the device that made it. */
    val changedAt: Long,
    val deviceId: String,
    val serverSeq: Long? = null,
)
