package com.maxrave.data.librarysync

import com.maxrave.domain.data.entities.sync.SyncChangeEntity
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncSupportTest {
    private fun change(
        at: Long,
        value: String = ON,
        op: String = SyncOp.SET,
        id: String = "a",
    ) = SyncChangeEntity(changeId = id, entity = SyncKind.SONG_LIKE, entityKey = "v", op = op, value = value, changedAt = at, deviceId = "d")

    @Test
    fun theNewestChangeWins() {
        assertTrue(change(at = 2, value = OFF).supersedes(change(at = 1)))
        assertFalse(change(at = 1).supersedes(change(at = 2, value = OFF)))
        assertTrue(change(at = 1).supersedes(null))
    }

    @Test
    fun aTieKeepsTheItemAndThenTheChangeIdDecides() {
        // A like and an unlike in the same millisecond: the song stays liked on every device.
        assertTrue(change(at = 5, value = ON, id = "a").supersedes(change(at = 5, value = OFF, id = "z")))
        assertFalse(change(at = 5, value = OFF, id = "z").supersedes(change(at = 5, value = ON, id = "a")))
        // Same moment, same direction: both devices pick the same one.
        assertTrue(change(at = 5, id = "b").supersedes(change(at = 5, id = "a")))
        assertFalse(change(at = 5, id = "a").supersedes(change(at = 5, id = "b")))
    }

    @Test
    fun onlyRemovalsCountAsRemovals() {
        assertTrue(change(at = 1, value = OFF).removesSomething)
        assertTrue(change(at = 1, op = SyncOp.DELETE, value = null).removesSomething)
        assertFalse(change(at = 1, value = ON).removesSomething)
        assertFalse(change(at = 1, op = SyncOp.UPSERT, value = null).removesSomething)
        assertFalse(change(at = 1, op = SyncOp.ADD, value = null).removesSomething)
    }

    @Test
    fun aPlayKeepsItsWallClockAcrossDevices() {
        val played = LocalDateTime(2026, 11, 12, 20, 0, 5)
        assertEquals(played, played.toStoredMillis().storedMillisToDateTime())
    }

    @Test
    fun aChangeSurvivesTheWire() {
        val original = change(at = 42, value = OFF, id = "x")
        assertEquals(original, original.toWire().toEntity())
        assertEquals(7L, original.toWire().toEntity(serverSeq = 7).serverSeq)
    }

    @Test
    fun syncIdsAreVersionFourUuids() {
        val id = newSyncId()
        assertEquals(36, id.length)
        assertEquals('4', id[14])
        assertTrue(id[19] in "89ab")
    }
}
