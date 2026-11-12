package com.maxrave.data.librarysync

import com.maxrave.data.db.SyncDao
import com.maxrave.domain.data.entities.sync.SyncChangeEntity
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext

private const val TAG = "LibrarySync"

/**
 * Called by every write to a synced item (LocalDataSource, AnalyticsDatasource) to note the change
 * for the paired device. Records nothing while unpaired, and nothing while another device's changes
 * are being applied ([SyncApplying]). A phone keeps the change until the computer takes it; the
 * computer logs it at once with the next sequence number.
 *
 * A failure here is logged and swallowed: the user's own write has already happened and must not be
 * reported as failed because sync could not take note of it.
 */
internal class SyncChangeRecorder(
    private val dao: SyncDao,
    private val prefs: SyncPrefs,
) {
    /** Whether a change made now would be recorded. Cheap; lets callers skip reads they only need for sync. */
    suspend fun active(): Boolean = currentCoroutineContext()[SyncApplyingKey] == null && prefs.current().paired

    suspend fun flag(
        kind: String,
        key: String,
        on: Boolean,
    ) = record(kind, key, SyncOp.SET, if (on) ON else OFF)

    /** The playlist's name or cover changed, or it was created; what it now is is read when sent. */
    suspend fun playlistMeta(playlistId: Long) {
        if (!active()) return
        playlistSyncId(playlistId)?.let { record(SyncKind.LOCAL_PLAYLIST, it, SyncOp.UPSERT, null) }
    }

    suspend fun playlistDeleted(syncId: String) = record(SyncKind.LOCAL_PLAYLIST, syncId, SyncOp.DELETE, null)

    suspend fun playlistTrack(
        playlistId: Long,
        videoId: String,
        inPlaylist: Boolean,
    ) {
        if (!active()) return
        val syncId = playlistSyncId(playlistId) ?: return
        record(SyncKind.LOCAL_PLAYLIST_TRACK, "$syncId|$videoId", SyncOp.SET, if (inPlaylist) ON else OFF)
        // An added song lands at the end; the order is what puts it where the user sees it.
        record(SyncKind.LOCAL_PLAYLIST_ORDER, syncId, SyncOp.SET, null)
    }

    /** Songs moved in the playlist; the order itself is read when sent, so a run of moves sends one. */
    suspend fun playlistOrder(playlistId: Long) {
        if (!active()) return
        playlistSyncId(playlistId)?.let { record(SyncKind.LOCAL_PLAYLIST_ORDER, it, SyncOp.SET, null) }
    }

    suspend fun play(syncId: String) = record(SyncKind.HISTORY, syncId, SyncOp.ADD, null)

    /**
     * The playlist was linked to YouTube (or the link changed): it goes out whole, so every song
     * carries its YouTube entry id and the other side can edit the YouTube playlist too.
     */
    suspend fun playlistWhole(playlistId: Long) {
        if (!active()) return
        val playlist = dao.localPlaylist(playlistId) ?: return
        SyncSnapshot(dao).playlistChanges(playlist, prefs.deviceId(), nowMillis()).forEach { store(it) }
    }

    /** A song's entry id in a linked YouTube playlist arrived after the song was added: send the song again with it. */
    suspend fun setVideoIdAdded(
        videoId: String,
        youtubePlaylistId: String,
    ) {
        if (!active() || youtubePlaylistId.isEmpty()) return
        val playlist = dao.localPlaylistByYoutubeIds(youtubeIds(youtubePlaylistId)) ?: return
        if (dao.pairCount(playlist.id, videoId) == 0) return
        val syncId = playlistSyncId(playlist.id) ?: return
        record(SyncKind.LOCAL_PLAYLIST_TRACK, "$syncId|$videoId", SyncOp.SET, ON)
    }

    /** The playlist's syncId, given one now if it has none (a playlist made before sync existed). */
    suspend fun playlistSyncId(playlistId: Long): String? {
        val playlist = dao.localPlaylist(playlistId) ?: return null
        return playlist.syncId ?: newSyncId().also { dao.setLocalPlaylistSyncId(playlistId, it) }
    }

    private suspend fun record(
        kind: String,
        key: String,
        op: String,
        value: String?,
    ) {
        if (!active()) return
        store(
            SyncChangeEntity(
                changeId = newSyncId(),
                entity = kind,
                entityKey = key,
                op = op,
                value = value,
                changedAt = nowMillis(),
                deviceId = prefs.deviceId(),
            ),
        )
    }

    private suspend fun store(change: SyncChangeEntity) {
        try {
            if (prefs.current().phones.isNotEmpty()) dao.recordLogged(change) else dao.recordPending(change)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.e(TAG, "record ${change.entity} failed", e)
        }
    }
}
