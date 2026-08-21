package com.maxrave.data.db

import DatabaseDao
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.execSQL
import androidx.room.useWriterConnection
import com.maxrave.domain.data.entities.AlbumEntity
import com.maxrave.domain.data.entities.ArtistEntity
import com.maxrave.domain.data.entities.AutoEqCurveEntity
import com.maxrave.domain.data.entities.AutoEqEntryEntity
import com.maxrave.domain.data.entities.AutoEqIndexMetaEntity
import com.maxrave.domain.data.entities.EpisodeEntity
import com.maxrave.domain.data.entities.FollowedArtistSingleAndAlbum
import com.maxrave.domain.data.entities.GoogleAccountEntity
import com.maxrave.domain.data.entities.LocalPlaylistEntity
import com.maxrave.domain.data.entities.LyricsEntity
import com.maxrave.domain.data.entities.NewFormatEntity
import com.maxrave.domain.data.entities.NotificationEntity
import com.maxrave.domain.data.entities.PairSongLocalPlaylist
import com.maxrave.domain.data.entities.PlaylistEntity
import com.maxrave.domain.data.entities.PodcastsEntity
import com.maxrave.domain.data.entities.QueueEntity
import com.maxrave.domain.data.entities.SearchHistory
import com.maxrave.domain.data.entities.SetVideoIdEntity
import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.data.entities.SongInfoEntity
import com.maxrave.domain.data.entities.TranslatedLyricsEntity
import com.maxrave.domain.data.entities.YourYouTubePlaylistList
import com.maxrave.domain.data.entities.analytics.EventArtistEntity
import com.maxrave.domain.data.entities.analytics.PlaybackEventEntity

@Database(
    entities = [
        NewFormatEntity::class, SongInfoEntity::class, SearchHistory::class, SongEntity::class, ArtistEntity::class,
        AlbumEntity::class, PlaylistEntity::class, LocalPlaylistEntity::class, LyricsEntity::class, QueueEntity::class,
        SetVideoIdEntity::class, PairSongLocalPlaylist::class, GoogleAccountEntity::class, FollowedArtistSingleAndAlbum::class,
        NotificationEntity::class, TranslatedLyricsEntity::class, PodcastsEntity::class, EpisodeEntity::class,
        YourYouTubePlaylistList::class, PlaybackEventEntity::class, EventArtistEntity::class,
        AutoEqEntryEntity::class, AutoEqIndexMetaEntity::class, AutoEqCurveEntity::class
    ],
    version = 25,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 2, to = 3), AutoMigration(
            from = 1,
            to = 3,
        ), AutoMigration(from = 3, to = 4), AutoMigration(from = 2, to = 4), AutoMigration(
            from = 3,
            to = 5,
        ), AutoMigration(4, 5), AutoMigration(6, 7), AutoMigration(
            7,
            8,
            spec = AutoMigration7_8::class,
        ), AutoMigration(8, 9),
        AutoMigration(9, 10),
        AutoMigration(from = 11, to = 12, spec = AutoMigration11_12::class),
        AutoMigration(13, 14),
        AutoMigration(14, 15),
        AutoMigration(15, 16),
        AutoMigration(16, 17),
        AutoMigration(17, 18),
        AutoMigration(16, 18),
        AutoMigration(15, 18),
        AutoMigration(18, 19),
        AutoMigration(17, 19),
        AutoMigration(16, 19),
        AutoMigration(19, 20),
        AutoMigration(18, 20),
        AutoMigration(17, 20),
        AutoMigration(20, 21),
        AutoMigration(19, 21),
        AutoMigration(18, 21),
        AutoMigration(21, 22),
        AutoMigration(20, 22),
        AutoMigration(19, 22),
        AutoMigration(22, 23),
        AutoMigration(21, 23),
        AutoMigration(20, 23),
        AutoMigration(23, 24),
        AutoMigration(22, 24),
        AutoMigration(21, 24),
        // 25 adds the AutoEq cache. Three new tables and nothing else, so Room generates
        // the migration itself — no spec, and no path by which existing rows can be touched.
        AutoMigration(24, 25),
        AutoMigration(23, 25),
        AutoMigration(22, 25),
    ],
)
@TypeConverters(Converters::class)
abstract class MusicDatabase : RoomDatabase() {
    abstract fun getDatabaseDao(): DatabaseDao

    /**
     * Rewrite the database file so the pages a bulk delete freed go back to the filesystem.
     *
     * It lives here rather than on the DAO because the DAO has no way to ask for a **writer**
     * connection. `DatabaseDao.raw()` is the only door out to arbitrary SQL, and Room cannot parse
     * what a `@RawQuery` will do, so it generates `performSuspending(__db, isReadOnly = true, ...)`
     * for it — while a parsed `@Query` that deletes gets `isReadOnly = false`. Reader connections
     * are opened with `PRAGMA query_only = 1`, under which VACUUM fails outright with "attempt to
     * write a readonly database". `PRAGMA wal_checkpoint` is accepted on that very same connection,
     * which is why the sibling `DatabaseDao.checkpoint()` works and hid this for so long.
     *
     * [execSQL] prepares and steps the statement without opening a transaction, which is required:
     * SQLite refuses VACUUM inside one. Do not wrap this call in [androidx.room.Transactor.withTransaction].
     */
    suspend fun vacuum() {
        useWriterConnection { it.execSQL("VACUUM") }
    }
}

expect fun getDatabaseBuilder(converters: Converters): RoomDatabase.Builder<MusicDatabase>

expect fun getDatabasePath(): String