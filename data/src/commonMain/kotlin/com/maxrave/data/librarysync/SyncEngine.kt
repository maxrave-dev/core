package com.maxrave.data.librarysync

import com.maxrave.data.db.SyncDao
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.sync.SyncChangeEntity
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** Changes per request and per reply; a round loops until both sides are drained. */
internal const val SYNC_BATCH = 500

/** How long a phone keeps changes the computer already took, as the record of what it did. */
private const val SENT_KEPT_MS = 30L * 24 * 60 * 60 * 1000

/**
 * One round of library sync, both ends, transport aside. The phone sends what it changed and applies
 * what the computer sends back; the computer merges, logs and answers. Everything about the wire is
 * the platform repository's business; everything about the data is here.
 */
internal class SyncEngine(
    private val dao: SyncDao,
    private val prefs: SyncPrefs,
    private val applier: SyncApplier,
    private val settings: SyncedSettings,
) {
    private val snapshot = SyncSnapshot(dao)

    // ===== Phone =====

    /** A new pairing: whatever an earlier pairing left unsent is dropped, and this library goes out whole. */
    suspend fun startPhoneOutbox() {
        val deviceId = prefs.deviceId()
        dao.clearChanges()
        dao.insertChanges(snapshot.build(deviceId, includeHistory = settings.localTracking()))
    }

    suspend fun nextRequest(): SyncRequest {
        val deviceId = prefs.deviceId()
        val pending = dao.pending(SYNC_BATCH)
        val (wires, gone) = resolve(pending.map { it.toWire() })
        // Local tracking off here: plays are not sent, even ones noted before it was turned off.
        val events = if (settings.localTracking()) events(wires, deviceId) else emptyMap()
        val sendable = wires.filter { it.entity != SyncKind.HISTORY || it.key in events.keys }
        // A playlist deleted, or a play wiped, since the change was noted: nothing is left to send, and
        // left pending it would hold every later round open.
        val nothingLeft = gone + wires.filter { it.entity == SyncKind.HISTORY && it.key !in events.keys }.map { it.id }
        if (nothingLeft.isNotEmpty()) nothingLeft.chunked(CHUNK).forEach { dao.markSent(it, 0) }
        return SyncRequest(
            deviceId = deviceId,
            nonce = newSyncId(),
            since = prefs.current().lastServerSeq,
            changes = sendable,
            events = events.values.toList(),
            refs = refs(sendable, events.values),
            settings = settings.snapshot(),
        )
    }

    /**
     * Applies the computer's reply to [request]. Returns whether another round should follow.
     * [backup] runs first on the first round after pairing and when the reply removes something.
     */
    suspend fun applyReply(
        request: SyncRequest,
        reply: SyncResponse,
        desktopName: String,
        backup: suspend () -> Unit,
    ): Boolean {
        check(reply.nonce == request.nonce) { "the reply does not answer this request" }
        val deviceId = prefs.deviceId()
        if (reply.acked.isNotEmpty()) reply.acked.chunked(CHUNK).forEach { dao.markSent(it, 1) }
        if (request.since == 0L || reply.changes.any { it.toEntity().removesSomething }) backup()
        val events = reply.events.associateBy { it.syncId }
        val tracking = settings.localTracking()
        val sendWhole = mutableSetOf<Long>()
        withContext(SyncApplying) {
            applier.applyRefs(reply.refs)
            applier.ordered(reply.changes).forEach { change ->
                // Local tracking off here: the other device's plays are not taken.
                if (change.entity == SyncKind.HISTORY && !tracking) return@forEach
                // Something changed here while the request was out: that change is newer than this one
                // and goes in the next request instead. Checked right before applying, not up front.
                val mine = dao.pendingFor(change.entity, change.key)
                if (mine != null && mine.supersedes(change.toEntity())) return@forEach
                val applied = applier.applyOne(change, events, deviceId, sendWhole)
                if (!applied && change.isPlaylistDelete()) dao.localPlaylistBySyncId(change.key)?.let { sendWhole += it.id }
            }
        }
        // A deletion lost to an edit made here, the computer brought back a playlist it had deleted, or
        // a playlist here took the computer's syncId: send it again whole, so the computer gets every
        // song and not just the edit.
        reply.resend.forEach { syncId -> dao.localPlaylistBySyncId(syncId)?.let { sendWhole += it.id } }
        sendWhole.forEach { id ->
            dao.localPlaylist(id)?.let { playlist ->
                snapshot.playlistChanges(playlist, deviceId, nowMillis()).forEach { dao.recordPending(it) }
            }
        }
        prefs.update { it.copy(lastServerSeq = reply.newSeq, lastSyncAt = nowMillis()) }
        reply.settings?.let { settings.receive(it, desktopName) }
        dao.pruneSent(nowMillis() - SENT_KEPT_MS)
        return reply.hasMore || dao.pending(1).isNotEmpty()
    }

    // ===== Computer =====

    /** The first phone ever paired: the log starts from this computer's library as it stands. */
    suspend fun startHostLog() {
        val deviceId = prefs.deviceId()
        dao.clearChanges()
        dao.recordLoggedAll(snapshot.build(deviceId, includeHistory = settings.localTracking()))
    }

    /**
     * @param historyFrom plays older than this (in the database's own timestamp encoding) are never
     *   sent to this phone: it gets the year before it was paired and everything after.
     */
    suspend fun handle(
        request: SyncRequest,
        phoneName: String,
        historyFrom: Long,
        backup: suspend () -> Unit,
    ): SyncResponse {
        val ownId = prefs.deviceId()
        if (request.since == 0L || request.changes.any { it.toEntity().removesSomething }) backup()

        val acked = request.changes.map { it.id }
        val events = request.events.associateBy { it.syncId }
        val resend = mutableSetOf<String>()
        val lost = mutableListOf<SyncChangeEntity>()
        val sendWhole = mutableSetOf<Long>()
        // Local tracking off here: plays are neither taken from phones nor handed to them.
        val tracking = settings.localTracking()
        withContext(SyncApplying) { applier.applyRefs(request.refs) }
        applier.ordered(request.changes).forEach { change ->
            if (change.entity == SyncKind.HISTORY && !tracking) return@forEach
            if (dao.getChange(change.id) != null) return@forEach
            // Checked right before applying, so a change the user made here meanwhile is not overwritten.
            val latest = dao.latest(change.entity, change.key)
            if (!change.toEntity().supersedes(latest)) {
                // The phone may already be past that row (its clock ran behind); send it again so the
                // phone ends up where the log is.
                latest?.let { lost += it }
                return@forEach
            }
            val playlistId = change.playlistSyncId()
            val deletedHere = playlistId != null && dao.latest(SyncKind.LOCAL_PLAYLIST, playlistId)?.op == SyncOp.DELETE
            val applied = withContext(SyncApplying) { applier.applyOne(change, events, ownId, sendWhole) }
            if (applied && deletedHere && dao.localPlaylistBySyncId(playlistId!!) != null) resend += playlistId
            when {
                // Logged only once it holds here, so no phone is sent a state this computer does not have.
                applied -> dao.recordLogged(change.toEntity())
                // The deletion lost to a later edit: it is not logged (other phones would delete the
                // playlist), and the playlist is logged again whole, for the phone that deleted it.
                change.isPlaylistDelete() -> dao.localPlaylistBySyncId(change.key)?.let { sendWhole += it.id }
            }
        }
        // Playlists logged again whole: one whose deletion lost here, and one that took a phone's syncId.
        sendWhole.forEach { id ->
            dao.localPlaylist(id)?.let { playlist ->
                snapshot.playlistChanges(playlist, ownId, nowMillis()).forEach { dao.recordLogged(it) }
            }
        }
        request.settings?.let { settings.receive(it, phoneName) }

        val rows = dao.since(request.since, SYNC_BATCH)
        val newSeq = rows.lastOrNull()?.serverSeq ?: request.since
        val ackedIds = acked.toSet()
        // What this request just brought is not sent back. Everything else is, the phone's own older
        // changes included, so a phone pairing again converges on the log. Its own plays never come
        // back: a phone that cleared its history must not get it back from here.
        val pulled =
            rows.filter { it.changeId !in ackedIds && !(it.entity == SyncKind.HISTORY && it.deviceId == request.deviceId) }
        val pulledIds = pulled.map { it.changeId }.toSet()
        val theirs = (pulled + lost.filter { it.changeId !in pulledIds }.distinctBy { it.changeId }).map { it.toWire() }
        val (wires, _) = resolve(theirs)
        val sentEvents = if (tracking) events(wires, ownId).filterValues { it.timestamp >= historyFrom } else emptyMap()
        val sendable = wires.filter { it.entity != SyncKind.HISTORY || it.key in sentEvents.keys }
        return SyncResponse(
            nonce = request.nonce,
            acked = acked,
            changes = sendable,
            events = sentEvents.values.toList(),
            refs = refs(sendable, sentEvents.values),
            newSeq = newSeq,
            hasMore = rows.size == SYNC_BATCH,
            settings = settings.snapshot(),
            resend = resend.toList(),
        )
    }

    // ===== Both =====

    private fun ChangeWire.isPlaylistDelete() = entity == SyncKind.LOCAL_PLAYLIST && op == SyncOp.DELETE

    private fun LocalPlaylistEntity.toMeta() = PlaylistMeta(title, thumbnail, youtubePlaylistId)

    /** The local playlist an edit belongs to; null for a deletion and for every other kind. */
    private fun ChangeWire.playlistSyncId(): String? =
        when {
            entity == SyncKind.LOCAL_PLAYLIST && op == SyncOp.UPSERT -> key
            entity == SyncKind.LOCAL_PLAYLIST_ORDER -> key
            entity == SyncKind.LOCAL_PLAYLIST_TRACK -> key.substringBefore('|')
            else -> null
        }

    /**
     * Fills in what a change only points at: a playlist's name and cover, and its order, are read as
     * they are now. Returns the changes to send, and the ids of those whose playlist is gone.
     */
    private suspend fun resolve(changes: List<ChangeWire>): Pair<List<ChangeWire>, List<String>> {
        val kept = mutableListOf<ChangeWire>()
        val gone = mutableListOf<String>()
        val playlists = mutableMapOf<String, LocalPlaylistEntity?>()

        suspend fun playlist(syncId: String) = playlists.getOrPut(syncId) { dao.localPlaylistBySyncId(syncId) }
        changes.forEach { change ->
            when (change.entity) {
                SyncKind.LOCAL_PLAYLIST_TRACK -> {
                    // Carries the playlist along, in case the playlist's own row is in a later batch, and
                    // for a playlist linked to YouTube the song's entry id there.
                    val found = playlist(change.key.substringBefore('|'))
                    val youtubeId = found?.youtubePlaylistId
                    val setVideoId =
                        if (change.value == ON && youtubeId != null) {
                            dao.setVideoIdFor(change.key.substringAfter('|'), youtubeIds(youtubeId))
                        } else {
                            null
                        }
                    kept += change.copy(meta = found?.toMeta(), setVideoId = setVideoId)
                }

                SyncKind.LOCAL_PLAYLIST, SyncKind.LOCAL_PLAYLIST_ORDER -> {
                    if (change.entity == SyncKind.LOCAL_PLAYLIST && change.op != SyncOp.UPSERT) {
                        kept += change
                        return@forEach
                    }
                    val found = playlist(change.key)
                    if (found == null) {
                        gone += change.id
                        return@forEach
                    }
                    val meta = found.toMeta()
                    kept +=
                        if (change.entity == SyncKind.LOCAL_PLAYLIST) {
                            change.copy(value = syncJson.encodeToString(PlaylistMeta.serializer(), meta))
                        } else {
                            change.copy(value = syncJson.encodeToString(ListSerializer(String.serializer()), dao.playlistOrder(found.id).distinct()), meta = meta)
                        }
                }

                else -> {
                    kept += change
                }
            }
        }
        return kept to gone
    }

    /** The plays [changes] refer to, by syncId. A play this device made carries [ownId] as its device. */
    private suspend fun events(
        changes: List<ChangeWire>,
        ownId: String,
    ): Map<String, EventWire> {
        val ids = changes.filter { it.entity == SyncKind.HISTORY }.map { it.key }
        if (ids.isEmpty()) return emptyMap()
        return ids
            .chunked(CHUNK)
            .flatMap { dao.eventsBySyncId(it) }
            .associate { row ->
                val syncId = row.syncId!!
                syncId to
                    EventWire(
                        syncId = syncId,
                        timestamp = row.timestamp.toStoredMillis(),
                        videoId = row.videoId,
                        albumBrowseId = row.albumBrowseId,
                        durationSecond = row.durationSecond,
                        listenedSecond = row.listenedSecond,
                        artists = dao.eventArtists(row.eventId),
                        device = row.deviceId ?: ownId,
                    )
            }
    }

    /** The rows [changes] and [events] point at, for the other side to add where it lacks them. */
    private suspend fun refs(
        changes: List<ChangeWire>,
        events: Collection<EventWire>,
    ): SyncRefs {
        val songIds = mutableSetOf<String>()
        val albumIds = mutableSetOf<String>()
        val artistIds = mutableSetOf<String>()
        val playlistIds = mutableSetOf<String>()
        val podcastIds = mutableSetOf<String>()
        changes.forEach { change ->
            when (change.entity) {
                SyncKind.SONG_LIKE -> songIds += change.key
                SyncKind.ALBUM_LIKE -> albumIds += change.key
                SyncKind.ARTIST_FOLLOW -> artistIds += change.key
                SyncKind.PLAYLIST_LIKE -> playlistIds += change.key
                SyncKind.PODCAST_FAVORITE -> podcastIds += change.key
                SyncKind.LOCAL_PLAYLIST_TRACK -> if (change.value == ON) songIds += change.key.substringAfter('|')
            }
        }
        events.forEach { event ->
            songIds += event.videoId
            event.albumBrowseId?.let { albumIds += it }
            artistIds += event.artists
        }
        return SyncRefs(
            songs = songIds.chunked(CHUNK).flatMap { dao.songs(it) }.map { it.toRef() },
            albums = albumIds.chunked(CHUNK).flatMap { dao.albums(it) }.map { it.toRef() },
            artists = artistIds.chunked(CHUNK).flatMap { dao.artists(it) }.map { it.toRef() },
            playlists = playlistIds.chunked(CHUNK).flatMap { dao.playlists(it) }.map { it.toRef() },
            podcasts = podcastIds.chunked(CHUNK).flatMap { dao.podcasts(it) }.map { it.toRef() },
        )
    }

    private companion object {
        // Well under SQLite's bound-parameter limit.
        const val CHUNK = 400
    }
}
