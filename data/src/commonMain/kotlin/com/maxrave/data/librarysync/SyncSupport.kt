package com.maxrave.data.librarysync

import com.maxrave.domain.data.entities.sync.SyncChangeEntity
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** What a `sync_change` row is about. Keys and values per kind are documented on each. */
internal object SyncKind {
    /** key: videoId, value "1"/"0". */
    const val SONG_LIKE = "song_like"

    /** key: channelId, value "1"/"0". */
    const val ARTIST_FOLLOW = "artist_follow"

    /** key: album browseId, value "1"/"0". */
    const val ALBUM_LIKE = "album_like"

    /** key: YouTube playlist id, value "1"/"0". */
    const val PLAYLIST_LIKE = "playlist_like"

    /** key: podcastId, value "1"/"0". */
    const val PODCAST_FAVORITE = "podcast_fav"

    /** key: local playlist syncId; UPSERT carries [PlaylistMeta] (filled in when sent), DELETE nothing. */
    const val LOCAL_PLAYLIST = "lp"

    /** key: "<syncId>|<videoId>", value "1" in the playlist, "0" taken out. */
    const val LOCAL_PLAYLIST_TRACK = "lp_track"

    /** key: local playlist syncId; value the videoIds in order (filled in when sent). */
    const val LOCAL_PLAYLIST_ORDER = "lp_order"

    /** key: the play's syncId; the play itself travels as an EventWire. */
    const val HISTORY = "history"
}

internal object SyncOp {
    const val SET = "SET"
    const val UPSERT = "UPSERT"
    const val DELETE = "DELETE"
    const val ADD = "ADD"
}

internal const val ON = "1"
internal const val OFF = "0"

/** How far back the first round after pairing reaches into listening history (owner's call). */
internal const val HISTORY_WINDOW_DAYS = 365

/** Whether the change turns something on: a like, a follow, a song in a playlist, a playlist kept. */
internal val SyncChangeEntity.isOn: Boolean
    get() = op == SyncOp.UPSERT || op == SyncOp.ADD || (op == SyncOp.SET && value == ON)

/**
 * The newest change to an item wins. On a tie the one that turns something on wins, so a like and an
 * unlike stamped the same millisecond keep the song liked, and the change id breaks what is left so
 * every device decides the same way.
 */
internal fun SyncChangeEntity.supersedes(other: SyncChangeEntity?): Boolean {
    if (other == null) return true
    if (changedAt != other.changedAt) return changedAt > other.changedAt
    if (isOn != other.isOn) return isOn
    return changeId > other.changeId
}

/** A deletion of some kind; a round carrying one is preceded by a database backup. */
internal val SyncChangeEntity.removesSomething: Boolean
    get() = op == SyncOp.DELETE || (op == SyncOp.SET && value == OFF)

/** A random version-4 UUID string. Not for keys: those come from SecureRandom in the transport. */
internal fun newSyncId(): String {
    val bytes = Random.nextBytes(16)
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = bytes.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}

@OptIn(ExperimentalTime::class)
internal fun nowMillis(): Long = Clock.System.now().toEpochMilliseconds()

/** A wall-clock time this app stored (favoriteAt, followedAt…) as the real moment, read on this device's zone. */
@OptIn(ExperimentalTime::class)
internal fun LocalDateTime.wallToMillis(): Long = toInstant(TimeZone.currentSystemDefault()).toEpochMilliseconds()

@OptIn(ExperimentalTime::class)
internal fun Long.millisToWall(): LocalDateTime = Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.currentSystemDefault())

/**
 * A play's timestamp exactly as the database stores it: the local wall clock encoded as UTC (see
 * Converters). Carried this way so a play at 20:00 on the phone is a play at 20:00 on the computer.
 */
@OptIn(ExperimentalTime::class)
internal fun LocalDateTime.toStoredMillis(): Long = toInstant(TimeZone.UTC).toEpochMilliseconds()

@OptIn(ExperimentalTime::class)
internal fun Long.storedMillisToDateTime(): LocalDateTime = Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.UTC)

/**
 * In the coroutine context while another device's changes are being written, so the write hooks in
 * LocalDataSource do not record them again as this device's own.
 */
internal object SyncApplying : AbstractCoroutineContextElement(SyncApplyingKey)

internal object SyncApplyingKey : CoroutineContext.Key<SyncApplying>
