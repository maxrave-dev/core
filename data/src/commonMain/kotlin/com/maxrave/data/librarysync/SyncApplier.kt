package com.maxrave.data.librarysync

import com.maxrave.data.db.SyncDao
import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.PairSongLocalPlaylist
import com.maxrave.domain.data.entities.SetVideoIdEntity
import com.maxrave.domain.data.entities.analytics.PlaybackEventEntity
import com.maxrave.domain.extension.now
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

private const val TAG = "LibrarySync"

/**
 * Writes the other device's changes into this database, one at a time; [SyncEngine] decides which
 * changes win and calls [applyOne] for each, inside [SyncApplying], so the write hooks do not record
 * them back as this device's own. Everything goes through LocalDataSource or SyncDao, never a
 * repository, so nothing it does reaches YouTube.
 */
internal class SyncApplier(
    private val dao: SyncDao,
    private val local: LocalDataSource,
) {
    suspend fun applyRefs(refs: SyncRefs) {
        refs.songs.forEach { dao.insertSongIfMissing(it.toEntity()) }
        refs.albums.forEach { dao.insertAlbumIfMissing(it.toEntity()) }
        refs.artists.forEach { dao.insertArtistIfMissing(it.toEntity()) }
        refs.playlists.forEach { dao.insertPlaylistIfMissing(it.toEntity()) }
        refs.podcasts.forEach { dao.insertPodcastIfMissing(it.toEntity()) }
    }

    /** A playlist exists before songs go into it, songs are in it before they are ordered, and it is deleted last. */
    fun ordered(changes: List<ChangeWire>): List<ChangeWire> = changes.sortedWith(compareBy({ stage(it) }, { it.at }))

    private fun stage(change: ChangeWire): Int =
        when {
            change.entity == SyncKind.LOCAL_PLAYLIST && change.op == SyncOp.UPSERT -> 0
            change.entity == SyncKind.LOCAL_PLAYLIST_ORDER -> 2
            change.entity == SyncKind.LOCAL_PLAYLIST && change.op == SyncOp.DELETE -> 3
            else -> 1
        }

    /**
     * Returns whether this database now holds what the change says. False when it was skipped: a
     * song for a playlist this side deleted later, a deletion that lost to a later edit here, a play
     * already present, or a failure. The computer logs only changes that returned true, so a phone
     * is never sent a state the computer does not have.
     *
     * @param ownDeviceId plays this device made itself come back marked as its own.
     * @param sendWhole collects local playlists this side must send again whole: one that just took
     *   the other device's syncId because both were linked to the same YouTube playlist.
     */
    suspend fun applyOne(
        change: ChangeWire,
        events: Map<String, EventWire>,
        ownDeviceId: String,
        sendWhole: MutableSet<Long>,
    ): Boolean =
        try {
            val on = change.value == ON
            val at = if (on) change.at.millisToWall() else null
            when (change.entity) {
                SyncKind.SONG_LIKE -> dao.setSongLiked(change.key, on, at).let { true }
                SyncKind.ARTIST_FOLLOW -> dao.setArtistFollowed(change.key, on, at).let { true }
                SyncKind.ALBUM_LIKE -> dao.setAlbumLiked(change.key, on, at).let { true }
                SyncKind.PLAYLIST_LIKE -> dao.setPlaylistLiked(change.key, on, at).let { true }
                SyncKind.PODCAST_FAVORITE -> dao.setPodcastFavorite(change.key, on, at).let { true }
                SyncKind.LOCAL_PLAYLIST -> if (change.op == SyncOp.DELETE) deletePlaylist(change) else upsertPlaylist(change, sendWhole)
                SyncKind.LOCAL_PLAYLIST_TRACK -> applyTrack(change, on, sendWhole)
                SyncKind.LOCAL_PLAYLIST_ORDER -> applyOrder(change, sendWhole)
                SyncKind.HISTORY -> events[change.key]?.let { addPlay(it, ownDeviceId) } ?: false
                else -> false.also { Logger.w(TAG, "unknown change kind ${change.entity}") }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // A song row that never arrived, a playlist deleted meanwhile: skip it, the rest of the round is still worth having.
            Logger.e(TAG, "apply ${change.entity} failed", e)
            false
        }

    private suspend fun upsertPlaylist(
        change: ChangeWire,
        sendWhole: MutableSet<Long>,
    ): Boolean {
        val meta = change.value?.let { syncJson.decodeFromString(PlaylistMeta.serializer(), it) } ?: return false
        val existing = dao.localPlaylistBySyncId(change.key)
        if (existing == null) {
            ensurePlaylist(change.key, meta, sendWhole)
        } else {
            dao.setLocalPlaylistMeta(existing.id, meta.title, meta.thumbnail)
            dao.setLocalPlaylistLink(existing.id, meta.youtubePlaylistId, linkState(meta.youtubePlaylistId))
        }
        return true
    }

    /**
     * The local playlist with [syncId], made from [meta] when this side has none. A playlist here
     * linked to the same YouTube playlist is the same playlist imported on both devices: it takes the
     * other device's syncId instead of a copy being made, drops what was recorded under its old one,
     * and is sent again whole under the new one so the other side gets the songs only this side has.
     * Both sides adopt the syncId they receive, so they agree whichever row arrives first.
     */
    private suspend fun ensurePlaylist(
        syncId: String,
        meta: PlaylistMeta,
        sendWhole: MutableSet<Long>,
    ): LocalPlaylistEntity? {
        dao.localPlaylistBySyncId(syncId)?.let { return it }
        val twin = meta.youtubePlaylistId?.let { dao.localPlaylistByYoutubeIds(youtubeIds(it)) }
        if (twin != null) {
            twin.syncId?.let { old -> dao.deletePlaylistChanges(old, "$old|") }
            dao.setLocalPlaylistSyncId(twin.id, syncId)
            dao.setLocalPlaylistMeta(twin.id, meta.title, meta.thumbnail)
            sendWhole += twin.id
            return dao.localPlaylistBySyncId(syncId)
        }
        local.insertLocalPlaylist(
            LocalPlaylistEntity(
                title = meta.title,
                thumbnail = meta.thumbnail,
                tracks = emptyList(),
                youtubePlaylistId = meta.youtubePlaylistId,
                syncState = linkState(meta.youtubePlaylistId),
                syncId = syncId,
            ),
        )
        return dao.localPlaylistBySyncId(syncId)
    }

    private fun linkState(youtubePlaylistId: String?) =
        if (youtubePlaylistId != null) LocalPlaylistEntity.YouTubeSyncState.Synced else LocalPlaylistEntity.YouTubeSyncState.NotSynced

    /**
     * The playlist a song or order change belongs to, made from the change's [ChangeWire.meta] when
     * its own row has not arrived yet — unless this side deleted it after the change was made.
     */
    private suspend fun playlistFor(
        syncId: String,
        change: ChangeWire,
        sendWhole: MutableSet<Long>,
    ): LocalPlaylistEntity? {
        dao.localPlaylistBySyncId(syncId)?.let { return it }
        val meta = change.meta ?: return null
        val last = dao.latest(SyncKind.LOCAL_PLAYLIST, syncId)
        if (last != null && last.op == SyncOp.DELETE && last.changedAt >= change.at) return null
        return ensurePlaylist(syncId, meta, sendWhole)
    }

    /**
     * False when the deletion lost to a later edit and the playlist was kept. The edits weighed are
     * the ones this side knows: on a phone its own (sent or not), on the computer the whole log.
     */
    private suspend fun deletePlaylist(change: ChangeWire): Boolean {
        val playlist = dao.localPlaylistBySyncId(change.key) ?: return true
        val prefix = "${change.key}|"
        val lastEdit = dao.latestPlaylistEdit(change.key, prefix)
        if (lastEdit != null && lastEdit > change.at) return false
        local.deleteLocalPlaylist(playlist.id)
        return true
    }

    private suspend fun applyTrack(
        change: ChangeWire,
        inPlaylist: Boolean,
        sendWhole: MutableSet<Long>,
    ): Boolean {
        val syncId = change.key.substringBefore('|')
        val videoId = change.key.substringAfter('|')
        val playlist = if (inPlaylist) playlistFor(syncId, change, sendWhole) else dao.localPlaylistBySyncId(syncId)
        // Taking a song out of a playlist this side does not have leaves it as the change says.
        if (playlist == null) return !inPlaylist
        val present = dao.pairCount(playlist.id, videoId) > 0
        if (inPlaylist && !present) {
            local.insertPairSongLocalPlaylist(
                PairSongLocalPlaylist(
                    playlistId = playlist.id,
                    songId = videoId,
                    position = dao.lastPosition(playlist.id) + 1,
                    inPlaylist = now(),
                ),
            )
            local.updateLocalPlaylistTracks((playlist.tracks ?: emptyList()) + videoId, playlist.id)
        }
        val youtubeId = playlist.youtubePlaylistId
        if (inPlaylist && youtubeId != null && change.setVideoId != null) {
            // So removing the song here also removes it from the YouTube playlist.
            local.insertSetVideoId(SetVideoIdEntity(videoId = videoId, setVideoId = change.setVideoId, youtubePlaylistId = youtubeId))
        }
        if (!inPlaylist && present) {
            local.deletePairSongLocalPlaylist(playlist.id, videoId)
            local.updateLocalPlaylistTracks((playlist.tracks ?: emptyList()).filterNot { it == videoId }, playlist.id)
        }
        return true
    }

    private suspend fun applyOrder(
        change: ChangeWire,
        sendWhole: MutableSet<Long>,
    ): Boolean {
        val wanted = change.value?.let { syncJson.decodeFromString(ListSerializer(String.serializer()), it) } ?: return false
        val playlist = playlistFor(change.key, change, sendWhole) ?: return false
        val current = dao.playlistOrder(playlist.id)
        // The order only arranges songs this side has; songs it has that the order does not name keep
        // their place at the end, and the order cannot add one.
        val present = current.toSet()
        val ordered = wanted.filter { it in present }.distinct()
        val rest = current.filterNot { it in ordered.toSet() }
        val next = ordered + rest
        if (next == current) return true
        next.forEachIndexed { index, videoId -> dao.setPosition(playlist.id, videoId, index) }
        local.updateLocalPlaylistTracks(next, playlist.id)
        return true
    }

    private suspend fun addPlay(
        event: EventWire,
        ownDeviceId: String,
    ): Boolean {
        if (dao.eventCount(event.syncId) > 0) return false
        dao.insertEventWithArtists(
            PlaybackEventEntity(
                timestamp = event.timestamp.storedMillisToDateTime(),
                videoId = event.videoId,
                albumBrowseId = event.albumBrowseId,
                durationSecond = event.durationSecond,
                listenedSecond = event.listenedSecond,
                syncId = event.syncId,
                deviceId = event.device.takeUnless { it == ownDeviceId },
            ),
            event.artists,
        )
        return true
    }
}

/** A YouTube playlist id in both spellings the app stores: bare and with the "VL" browse prefix. */
internal fun youtubeIds(youtubePlaylistId: String): List<String> {
    val bare = youtubePlaylistId.removePrefix("VL")
    return listOf(bare, "VL$bare")
}
