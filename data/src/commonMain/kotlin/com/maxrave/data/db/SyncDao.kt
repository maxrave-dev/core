package com.maxrave.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.maxrave.domain.data.entities.AlbumEntity
import com.maxrave.domain.data.entities.ArtistEntity
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.PlaylistEntity
import com.maxrave.domain.data.entities.PodcastsEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.entities.analytics.EventArtistEntity
import com.maxrave.domain.data.entities.analytics.PlaybackEventEntity
import com.maxrave.domain.data.entities.sync.SyncChangeEntity
import kotlinx.datetime.LocalDateTime

/**
 * Queries for library sync (#2663). Kept apart from DatabaseDao so everything that reads or writes
 * `sync_change`, and the IGNORE inserts that bring in another device's rows, sit in one place.
 */
@Dao
interface SyncDao {
    // ===== sync_change =====

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChange(change: SyncChangeEntity)

    @Query("SELECT * FROM sync_change WHERE changeId = :changeId")
    suspend fun getChange(changeId: String): SyncChangeEntity?

    /** Phone: a newer local change to the same item replaces the one still waiting to be sent. */
    @Query("DELETE FROM sync_change WHERE entity = :entity AND entityKey = :entityKey AND serverSeq IS NULL")
    suspend fun deletePending(
        entity: String,
        entityKey: String,
    )

    @Query("SELECT * FROM sync_change WHERE serverSeq IS NULL ORDER BY changedAt LIMIT :limit")
    suspend fun pending(limit: Int): List<SyncChangeEntity>

    @Query("SELECT * FROM sync_change WHERE serverSeq IS NULL AND entity = :entity AND entityKey = :entityKey LIMIT 1")
    suspend fun pendingFor(
        entity: String,
        entityKey: String,
    ): SyncChangeEntity?

    /** Phone: the computer took these; they stay a while as the record of what this device did. */
    @Query("UPDATE sync_change SET serverSeq = :seq WHERE changeId IN (:changeIds)")
    suspend fun markSent(
        changeIds: List<String>,
        seq: Long,
    )

    @Query("DELETE FROM sync_change WHERE serverSeq IS NOT NULL AND changedAt < :before")
    suspend fun pruneSent(before: Long)

    @Query("SELECT COALESCE(MAX(serverSeq), 0) FROM sync_change")
    suspend fun maxSeq(): Long

    @Query("SELECT * FROM sync_change WHERE entity = :entity AND entityKey = :entityKey ORDER BY changedAt DESC LIMIT 1")
    suspend fun latest(
        entity: String,
        entityKey: String,
    ): SyncChangeEntity?

    /** Computer: only the newest change per item is ever needed, so the older ones go. */
    @Query("DELETE FROM sync_change WHERE entity = :entity AND entityKey = :entityKey AND changeId != :keep")
    suspend fun deleteOthers(
        entity: String,
        entityKey: String,
        keep: String,
    )

    @Query("SELECT * FROM sync_change WHERE serverSeq > :since ORDER BY serverSeq LIMIT :limit")
    suspend fun since(
        since: Long,
        limit: Int,
    ): List<SyncChangeEntity>

    @Query("DELETE FROM sync_change")
    suspend fun clearChanges()

    /**
     * The newest edit to local playlist [syncId] — its name or cover, its order, or any song added to
     * or removed from it. A deletion older than this loses, so a playlist the other device was still
     * editing is kept. `substr` instead of LIKE so nothing in a key can act as a wildcard.
     */
    @Query(
        "SELECT MAX(changedAt) FROM sync_change WHERE " +
            "(entity = 'lp' AND entityKey = :syncId AND op != 'DELETE') OR " +
            "(entity = 'lp_order' AND entityKey = :syncId) OR " +
            "(entity = 'lp_track' AND substr(entityKey, 1, length(:prefix)) = :prefix)",
    )
    suspend fun latestPlaylistEdit(
        syncId: String,
        prefix: String,
    ): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChanges(changes: List<SyncChangeEntity>)

    /** Computer: the first round's state of this device, each change on the next sequence number. */
    @Transaction
    suspend fun recordLoggedAll(changes: List<SyncChangeEntity>) {
        var seq = maxSeq()
        changes.forEach { insertChange(it.copy(serverSeq = ++seq)) }
    }

    /** Phone: a change is recorded with the item; a newer one to the same item replaces it. */
    @Transaction
    suspend fun recordPending(change: SyncChangeEntity) {
        deletePending(change.entity, change.entityKey)
        insertChange(change)
    }

    /** Computer: the change gets the next sequence number and becomes the only one kept for its item. */
    @Transaction
    suspend fun recordLogged(change: SyncChangeEntity): Long {
        val seq = maxSeq() + 1
        insertChange(change.copy(serverSeq = seq))
        // A play is never superseded: every one of them is its own key.
        if (change.entity != "history") deleteOthers(change.entity, change.entityKey, change.changeId)
        return seq
    }

    // ===== Rows another device sends: added when missing, never written over =====

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSongIfMissing(song: SongEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAlbumIfMissing(album: AlbumEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArtistIfMissing(artist: ArtistEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPlaylistIfMissing(playlist: PlaylistEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPodcastIfMissing(podcast: PodcastsEntity): Long

    @Query("SELECT * FROM song WHERE videoId IN (:ids)")
    suspend fun songs(ids: List<String>): List<SongEntity>

    @Query("SELECT * FROM album WHERE browseId IN (:ids)")
    suspend fun albums(ids: List<String>): List<AlbumEntity>

    @Query("SELECT * FROM artist WHERE channelId IN (:ids)")
    suspend fun artists(ids: List<String>): List<ArtistEntity>

    @Query("SELECT * FROM playlist WHERE id IN (:ids)")
    suspend fun playlists(ids: List<String>): List<PlaylistEntity>

    @Query("SELECT * FROM podcast_table WHERE podcastId IN (:ids)")
    suspend fun podcasts(ids: List<String>): List<PodcastsEntity>

    // ===== Flags, applied from another device =====

    @Query("UPDATE song SET liked = :liked, favoriteAt = :at WHERE videoId = :videoId")
    suspend fun setSongLiked(
        videoId: String,
        liked: Boolean,
        at: LocalDateTime?,
    )

    @Query("UPDATE artist SET followed = :followed, followedAt = :at WHERE channelId = :channelId")
    suspend fun setArtistFollowed(
        channelId: String,
        followed: Boolean,
        at: LocalDateTime?,
    )

    @Query("UPDATE album SET liked = :liked, favoriteAt = :at WHERE browseId = :browseId")
    suspend fun setAlbumLiked(
        browseId: String,
        liked: Boolean,
        at: LocalDateTime?,
    )

    @Query("UPDATE playlist SET liked = :liked, favoriteAt = :at WHERE id = :id")
    suspend fun setPlaylistLiked(
        id: String,
        liked: Boolean,
        at: LocalDateTime?,
    )

    @Query("UPDATE podcast_table SET isFavorite = :favorite, favoriteTime = :at WHERE podcastId = :podcastId")
    suspend fun setPodcastFavorite(
        podcastId: String,
        favorite: Boolean,
        at: LocalDateTime?,
    )

    // ===== What this device already has, for the first round after pairing =====

    @Query("SELECT * FROM song WHERE liked = 1")
    suspend fun likedSongs(): List<SongEntity>

    @Query("SELECT * FROM artist WHERE followed = 1")
    suspend fun followedArtists(): List<ArtistEntity>

    @Query("SELECT * FROM album WHERE liked = 1")
    suspend fun likedAlbums(): List<AlbumEntity>

    @Query("SELECT * FROM playlist WHERE liked = 1")
    suspend fun likedPlaylists(): List<PlaylistEntity>

    @Query("SELECT * FROM podcast_table WHERE isFavorite = 1")
    suspend fun favoritePodcasts(): List<PodcastsEntity>

    // ===== Local playlists =====

    @Query("SELECT * FROM local_playlist")
    suspend fun localPlaylists(): List<LocalPlaylistEntity>

    @Query("SELECT * FROM local_playlist WHERE id = :id")
    suspend fun localPlaylist(id: Long): LocalPlaylistEntity?

    @Query("SELECT * FROM local_playlist WHERE syncId = :syncId LIMIT 1")
    suspend fun localPlaylistBySyncId(syncId: String): LocalPlaylistEntity?

    @Query("UPDATE local_playlist SET syncId = :syncId WHERE id = :id")
    suspend fun setLocalPlaylistSyncId(
        id: Long,
        syncId: String,
    )

    /** A local playlist linked to YouTube playlist [youtubeIds] (the bare id and its "VL" spelling). */
    @Query("SELECT * FROM local_playlist WHERE youtubePlaylistId IN (:youtubeIds) LIMIT 1")
    suspend fun localPlaylistByYoutubeIds(youtubeIds: List<String>): LocalPlaylistEntity?

    @Query("UPDATE local_playlist SET youtubePlaylistId = :youtubePlaylistId, youtube_sync_state = :syncState WHERE id = :id")
    suspend fun setLocalPlaylistLink(
        id: Long,
        youtubePlaylistId: String?,
        syncState: Int,
    )

    /** The YouTube entry id of [videoId] in a linked playlist, needed there to remove the song from YouTube. */
    @Query(
        "SELECT setVideoId FROM set_video_id WHERE videoId = :videoId AND youtubePlaylistId IN (:youtubeIds) " +
            "AND setVideoId IS NOT NULL LIMIT 1",
    )
    suspend fun setVideoIdFor(
        videoId: String,
        youtubeIds: List<String>,
    ): String?

    /** Every change recorded for local playlist [syncId]; used when the playlist takes another device's syncId. */
    @Query(
        "DELETE FROM sync_change WHERE ((entity = 'lp' OR entity = 'lp_order') AND entityKey = :syncId) OR " +
            "(entity = 'lp_track' AND substr(entityKey, 1, length(:prefix)) = :prefix)",
    )
    suspend fun deletePlaylistChanges(
        syncId: String,
        prefix: String,
    )

    @Query("SELECT songId FROM pair_song_local_playlist WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun playlistOrder(playlistId: Long): List<String>

    @Query("SELECT COUNT(*) FROM pair_song_local_playlist WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun pairCount(
        playlistId: Long,
        songId: String,
    ): Int

    @Query("SELECT COALESCE(MAX(position), -1) FROM pair_song_local_playlist WHERE playlistId = :playlistId")
    suspend fun lastPosition(playlistId: Long): Int

    @Query("UPDATE pair_song_local_playlist SET position = :position WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun setPosition(
        playlistId: Long,
        songId: String,
        position: Int,
    )

    @Query("UPDATE local_playlist SET title = :title, thumbnail = :thumbnail WHERE id = :id")
    suspend fun setLocalPlaylistMeta(
        id: Long,
        title: String,
        thumbnail: String?,
    )

    // ===== Listening history =====

    @Query("SELECT * FROM playback_event WHERE timestamp >= :from AND syncId IS NULL")
    suspend fun eventsWithoutSyncId(from: LocalDateTime): List<PlaybackEventEntity>

    @Query("UPDATE playback_event SET syncId = :syncId WHERE eventId = :eventId")
    suspend fun setEventSyncId(
        eventId: Long,
        syncId: String,
    )

    /** This device's own plays (deviceId is null on them) from [from] on that carry a syncId. */
    @Query("SELECT syncId FROM playback_event WHERE timestamp >= :from AND deviceId IS NULL AND syncId IS NOT NULL")
    suspend fun ownEventSyncIdsSince(from: LocalDateTime): List<String>

    @Query("SELECT * FROM playback_event WHERE syncId IN (:syncIds)")
    suspend fun eventsBySyncId(syncIds: List<String>): List<PlaybackEventEntity>

    @Query("SELECT channelId FROM event_artist WHERE eventId = :eventId")
    suspend fun eventArtists(eventId: Long): List<String>

    @Query("SELECT COUNT(*) FROM playback_event WHERE syncId = :syncId")
    suspend fun eventCount(syncId: String): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEvent(event: PlaybackEventEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEventArtist(eventArtist: EventArtistEntity)

    /** Another device's play, with its artists, in one go so a crash cannot leave half of it. */
    @Transaction
    suspend fun insertEventWithArtists(
        event: PlaybackEventEntity,
        channelIds: List<String>,
    ) {
        val eventId = insertEvent(event)
        if (eventId == -1L) return
        channelIds.distinct().forEach { insertEventArtist(EventArtistEntity(eventId = eventId, channelId = it, timestamp = event.timestamp)) }
    }

}
