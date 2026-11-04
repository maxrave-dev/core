package com.maxrave.data.db

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * v28 lets a local playlist hold an album as a single entry.
 *
 * `pair_song_local_playlist.songId` becomes nullable (an album row has no song) and gains
 * `itemType`, `albumBrowseId`, `title` and `thumbnail`. SQLite cannot drop a column's NOT NULL
 * constraint in place, so the table is rebuilt: copy every existing row as an [PairSongLocalPlaylist.ITEM_SONG]
 * entry, then swap the tables. The existing rows are untouched in meaning — a song is still a song.
 *
 * Room validates the resulting schema against the entity after this runs (column types, NOT NULL
 * flags, defaults), which is why the new table is declared to match exactly.
 */
object Migration27_28 : Migration(27, 28) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            "CREATE TABLE IF NOT EXISTS `pair_song_local_playlist_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `playlistId` INTEGER NOT NULL, `songId` TEXT, `position` INTEGER NOT NULL, `inPlaylist` INTEGER NOT NULL, `itemType` INTEGER NOT NULL, `albumBrowseId` TEXT, `title` TEXT, `thumbnail` TEXT, FOREIGN KEY(`playlistId`) REFERENCES `local_playlist`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE , FOREIGN KEY(`songId`) REFERENCES `song`(`videoId`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        connection.execSQL(
            "INSERT INTO `pair_song_local_playlist_new` (`id`, `playlistId`, `songId`, `position`, `inPlaylist`, `itemType`) " +
                "SELECT `id`, `playlistId`, `songId`, `position`, `inPlaylist`, 0 FROM `pair_song_local_playlist`",
        )
        connection.execSQL("DROP TABLE `pair_song_local_playlist`")
        connection.execSQL("ALTER TABLE `pair_song_local_playlist_new` RENAME TO `pair_song_local_playlist`")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_pair_song_local_playlist_playlistId` ON `pair_song_local_playlist` (`playlistId`)")
        connection.execSQL("CREATE INDEX IF NOT EXISTS `index_pair_song_local_playlist_songId` ON `pair_song_local_playlist` (`songId`)")
    }
}
