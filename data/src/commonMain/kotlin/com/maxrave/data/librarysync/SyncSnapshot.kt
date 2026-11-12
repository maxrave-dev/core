package com.maxrave.data.librarysync

import com.maxrave.data.db.SyncDao
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.sync.SyncChangeEntity
import com.maxrave.domain.extension.beforeXDays
import com.maxrave.domain.extension.now
import kotlinx.datetime.LocalDateTime

/**
 * What this device already has, written out as changes for the first round after pairing: every
 * liked song, followed artist, hearted album / playlist / podcast, every local playlist with its
 * songs and order, and the plays of the last year. Only "on" changes, so the first round merges the
 * two libraries and removes nothing.
 */
internal class SyncSnapshot(
    private val dao: SyncDao,
) {
    /** @param includeHistory false while local tracking is off here: such a device neither sends nor takes plays. */
    suspend fun build(
        deviceId: String,
        includeHistory: Boolean,
    ): List<SyncChangeEntity> {
        val changes = mutableListOf<SyncChangeEntity>()

        fun add(
            kind: String,
            key: String,
            op: String,
            value: String?,
            at: LocalDateTime?,
        ) {
            changes += SyncChangeEntity(newSyncId(), kind, key, op, value, at?.wallToMillis() ?: 0L, deviceId)
        }

        dao.likedSongs().forEach { add(SyncKind.SONG_LIKE, it.videoId, SyncOp.SET, ON, it.favoriteAt ?: it.inLibrary) }
        dao.followedArtists().forEach { add(SyncKind.ARTIST_FOLLOW, it.channelId, SyncOp.SET, ON, it.followedAt ?: it.inLibrary) }
        dao.likedAlbums().forEach { add(SyncKind.ALBUM_LIKE, it.browseId, SyncOp.SET, ON, it.favoriteAt ?: it.inLibrary) }
        dao.likedPlaylists().forEach { add(SyncKind.PLAYLIST_LIKE, it.id, SyncOp.SET, ON, it.favoriteAt ?: it.inLibrary) }
        dao.favoritePodcasts().forEach { add(SyncKind.PODCAST_FAVORITE, it.podcastId, SyncOp.SET, ON, it.favoriteTime ?: it.inLibrary) }

        dao.localPlaylists().forEach { playlist ->
            changes += playlistChanges(playlist, deviceId, playlist.inLibrary.wallToMillis())
        }

        if (!includeHistory) return changes
        val from = now().beforeXDays(HISTORY_WINDOW_DAYS)
        dao.eventsWithoutSyncId(from).forEach { dao.setEventSyncId(it.eventId, newSyncId()) }
        // A play's key never repeats, so its time only orders the round; the stored encoding will do.
        dao.ownEventSyncIdsSince(from).forEach { syncId ->
            changes += SyncChangeEntity(newSyncId(), SyncKind.HISTORY, syncId, SyncOp.ADD, null, 0L, deviceId)
        }
        return changes
    }

    /**
     * One local playlist written out whole: itself, every song in it, and its order. Also used when a
     * deletion from the other device loses to an edit made here, so the side that deleted it gets the
     * whole playlist back rather than an empty one.
     */
    suspend fun playlistChanges(
        playlist: LocalPlaylistEntity,
        deviceId: String,
        at: Long,
    ): List<SyncChangeEntity> {
        val syncId = playlist.syncId ?: newSyncId().also { dao.setLocalPlaylistSyncId(playlist.id, it) }

        fun change(
            kind: String,
            key: String,
            op: String,
            value: String?,
        ) = SyncChangeEntity(newSyncId(), kind, key, op, value, at, deviceId)

        return buildList {
            add(change(SyncKind.LOCAL_PLAYLIST, syncId, SyncOp.UPSERT, null))
            dao.playlistOrder(playlist.id).distinct().forEach { videoId ->
                add(change(SyncKind.LOCAL_PLAYLIST_TRACK, "$syncId|$videoId", SyncOp.SET, ON))
            }
            add(change(SyncKind.LOCAL_PLAYLIST_ORDER, syncId, SyncOp.SET, null))
        }
    }
}
