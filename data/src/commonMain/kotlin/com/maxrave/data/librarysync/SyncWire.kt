package com.maxrave.data.librarysync

import com.maxrave.domain.data.entities.AlbumEntity
import com.maxrave.domain.data.entities.ArtistEntity
import com.maxrave.domain.data.entities.PlaylistEntity
import com.maxrave.domain.data.entities.PodcastsEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.entities.sync.SyncChangeEntity
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * What one round carries, phone to computer and back. The transport seals and compresses these bytes
 * and knows nothing of their shape.
 */

internal val syncJson = Json { ignoreUnknownKeys = true }

@Serializable
internal data class ChangeWire(
    val id: String,
    val entity: String,
    val key: String,
    val op: String,
    val value: String? = null,
    val at: Long,
    val device: String,
    /**
     * Songs and order only: the playlist they belong to, as it is when sent. Lets the other side
     * create the playlist when its own row has not arrived yet (it may sit in a later batch, since a
     * rename moves it to the end of the log). Never stored.
     */
    val meta: PlaylistMeta? = null,
    /** A song added to a playlist linked to YouTube: its entry id there, so the other side can remove it too. */
    val setVideoId: String? = null,
)

/** One play, as the database stores it; [timestamp] in the database's own encoding (toStoredMillis). */
@Serializable
internal data class EventWire(
    val syncId: String,
    val timestamp: Long,
    val videoId: String,
    val albumBrowseId: String? = null,
    val durationSecond: Long = 0,
    val listenedSecond: Long = 0,
    val artists: List<String> = emptyList(),
    val device: String,
)

@Serializable
internal data class PlaylistMeta(
    val title: String,
    val thumbnail: String? = null,
    /** The YouTube playlist it is linked to; null when it is not (or no longer) linked. */
    val youtubePlaylistId: String? = null,
)

// The rows a change points at, so the other device can show them. Only the columns that describe the
// item: download state, the Smart downloads marker, play time and canvas belong to each device.

@Serializable
internal data class SongRef(
    val videoId: String,
    val albumId: String? = null,
    val albumName: String? = null,
    val artistId: List<String>? = null,
    val artistName: List<String>? = null,
    val duration: String,
    val durationSeconds: Int,
    val isAvailable: Boolean,
    val isExplicit: Boolean,
    val likeStatus: String,
    val thumbnails: String? = null,
    val title: String,
    val videoType: String,
    val resultType: String? = null,
)

@Serializable
internal data class AlbumRef(
    val browseId: String,
    val artistId: List<String?>? = null,
    val artistName: List<String>? = null,
    val audioPlaylistId: String,
    val description: String,
    val duration: String? = null,
    val durationSeconds: Int,
    val thumbnails: String? = null,
    val title: String,
    val trackCount: Int,
    val tracks: List<String>? = null,
    val type: String,
    val year: String? = null,
)

@Serializable
internal data class ArtistRef(
    val channelId: String,
    val name: String,
    val thumbnails: String? = null,
)

@Serializable
internal data class PlaylistRef(
    val id: String,
    val author: String? = null,
    val description: String = "",
    val duration: String = "",
    val durationSeconds: Int = 0,
    val privacy: String = "PRIVATE",
    val thumbnails: String = "",
    val title: String,
    val trackCount: Int = 0,
    val tracks: List<String>? = null,
    val year: String? = null,
)

@Serializable
internal data class PodcastRef(
    val podcastId: String,
    val title: String,
    val authorId: String,
    val authorName: String,
    val authorThumbnail: String? = null,
    val description: String? = null,
    val thumbnail: String? = null,
    val listEpisodes: List<String> = emptyList(),
)

@Serializable
internal data class SyncRefs(
    val songs: List<SongRef> = emptyList(),
    val albums: List<AlbumRef> = emptyList(),
    val artists: List<ArtistRef> = emptyList(),
    val playlists: List<PlaylistRef> = emptyList(),
    val podcasts: List<PodcastRef> = emptyList(),
)

/** One allowlisted setting: its DataStore type ("s", "b", "i", "l", "f", "d", "ss") and value as text. */
@Serializable
internal data class SettingWire(
    val type: String,
    val value: String,
)

@Serializable
internal data class SettingsWire(
    val changedAt: Long,
    val values: Map<String, SettingWire>,
)

@Serializable
internal data class SyncRequest(
    val deviceId: String,
    /** Echoed in the reply, so an old sealed reply replayed by someone on the network is refused. */
    val nonce: String,
    val since: Long,
    val changes: List<ChangeWire> = emptyList(),
    val events: List<EventWire> = emptyList(),
    val refs: SyncRefs = SyncRefs(),
    val settings: SettingsWire? = null,
)

@Serializable
internal data class SyncResponse(
    val nonce: String,
    val acked: List<String> = emptyList(),
    val changes: List<ChangeWire> = emptyList(),
    val events: List<EventWire> = emptyList(),
    val refs: SyncRefs = SyncRefs(),
    val newSeq: Long,
    val hasMore: Boolean,
    val settings: SettingsWire? = null,
    /**
     * Local playlists the computer had deleted and has just brought back from an edit in this
     * request: it holds only what the edit carried, so the phone sends each one again whole.
     */
    val resend: List<String> = emptyList(),
)

internal fun SyncChangeEntity.toWire(): ChangeWire = ChangeWire(changeId, entity, entityKey, op, value, changedAt, deviceId)

internal fun ChangeWire.toEntity(serverSeq: Long? = null): SyncChangeEntity =
    SyncChangeEntity(
        changeId = id,
        entity = entity,
        entityKey = key,
        op = op,
        value = value,
        changedAt = at,
        deviceId = device,
        serverSeq = serverSeq,
    )

/**
 * Rows brought in from the other device enter with this as their `inLibrary`, so the "recently
 * added" view (getAllRecentData, newest inLibrary first) does not fill with the other device's items.
 */
internal val SYNCED_IN_LIBRARY = LocalDateTime(2000, 1, 1, 0, 0)

internal fun SongEntity.toRef() =
    SongRef(videoId, albumId, albumName, artistId, artistName, duration, durationSeconds, isAvailable, isExplicit, likeStatus, thumbnails, title, videoType, resultType)

internal fun SongRef.toEntity() =
    SongEntity(
        videoId = videoId,
        albumId = albumId,
        albumName = albumName,
        artistId = artistId,
        artistName = artistName,
        duration = duration,
        durationSeconds = durationSeconds,
        isAvailable = isAvailable,
        isExplicit = isExplicit,
        likeStatus = likeStatus,
        thumbnails = thumbnails,
        title = title,
        videoType = videoType,
        category = null,
        resultType = resultType,
        favoriteAt = null,
        downloadedAt = null,
        inLibrary = SYNCED_IN_LIBRARY,
    )

internal fun AlbumEntity.toRef() =
    AlbumRef(browseId, artistId, artistName, audioPlaylistId, description, duration, durationSeconds, thumbnails, title, trackCount, tracks, type, year)

internal fun AlbumRef.toEntity() =
    AlbumEntity(
        browseId = browseId,
        artistId = artistId,
        artistName = artistName,
        audioPlaylistId = audioPlaylistId,
        description = description,
        duration = duration,
        durationSeconds = durationSeconds,
        thumbnails = thumbnails,
        title = title,
        trackCount = trackCount,
        tracks = tracks,
        type = type,
        year = year,
        favoriteAt = null,
        downloadedAt = null,
        inLibrary = SYNCED_IN_LIBRARY,
    )

internal fun ArtistEntity.toRef() = ArtistRef(channelId, name, thumbnails)

internal fun ArtistRef.toEntity() =
    ArtistEntity(
        channelId = channelId,
        name = name,
        thumbnails = thumbnails,
        followedAt = null,
        inLibrary = SYNCED_IN_LIBRARY,
    )

internal fun PlaylistEntity.toRef() = PlaylistRef(id, author, description, duration, durationSeconds, privacy, thumbnails, title, trackCount, tracks, year)

internal fun PlaylistRef.toEntity() =
    PlaylistEntity(
        id = id,
        author = author,
        description = description,
        duration = duration,
        durationSeconds = durationSeconds,
        privacy = privacy,
        thumbnails = thumbnails,
        title = title,
        trackCount = trackCount,
        tracks = tracks,
        year = year,
        favoriteAt = null,
        downloadedAt = null,
        inLibrary = SYNCED_IN_LIBRARY,
    )

internal fun PodcastsEntity.toRef() = PodcastRef(podcastId, title, authorId, authorName, authorThumbnail, description, thumbnail, listEpisodes)

internal fun PodcastRef.toEntity() =
    PodcastsEntity(
        podcastId = podcastId,
        title = title,
        authorId = authorId,
        authorName = authorName,
        authorThumbnail = authorThumbnail,
        description = description,
        thumbnail = thumbnail,
        inLibrary = SYNCED_IN_LIBRARY,
        listEpisodes = listEpisodes,
    )
