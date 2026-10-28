package com.maxrave.data.db

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * v29 stores the album's artist beside its title and thumbnail, so an album row can draw the same
 * subtitle a song row does. The column is nullable, so the table is altered in place instead of
 * being rebuilt; every existing row (song and album alike) reads back `NULL`.
 */
object Migration28_29 : Migration(28, 29) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL("ALTER TABLE `pair_song_local_playlist` ADD COLUMN `artistName` TEXT")
    }
}
