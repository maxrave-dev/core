package com.maxrave.domain.data.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.maxrave.domain.extension.now
import kotlinx.datetime.LocalDateTime

/**
 * One ordered entry of a local playlist.
 *
 * Despite the name, a row is not always a song: [itemType] distinguishes a plain song
 * ([ITEM_SONG], the only kind that existed before) from an album added as a single entry
 * ([ITEM_ALBUM]). An album row carries no [songId] — the album is resolved from
 * [albumBrowseId] at play/navigation time — and stores only what is needed to draw its row
 * ([title], [thumbnail], [artistName]).
 */
@Entity(
    tableName = "pair_song_local_playlist",
    foreignKeys = [
        ForeignKey(
            entity = LocalPlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["videoId"],
            childColumns = ["songId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class PairSongLocalPlaylist(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(index = true) val playlistId: Long,
    @ColumnInfo(index = true) val songId: String? = null,
    val position: Int = 0,
    val inPlaylist: LocalDateTime = now(),
    val itemType: Int = ITEM_SONG,
    val albumBrowseId: String? = null,
    val title: String? = null,
    val thumbnail: String? = null,
    val artistName: String? = null,
) {
    companion object {
        const val ITEM_SONG = 0
        const val ITEM_ALBUM = 1
    }
}

/** True when this entry is a whole album rather than a single song. */
val PairSongLocalPlaylist.isAlbumItem: Boolean
    get() = itemType == PairSongLocalPlaylist.ITEM_ALBUM
