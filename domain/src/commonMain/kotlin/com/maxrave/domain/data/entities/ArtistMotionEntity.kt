package com.maxrave.domain.data.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Apple Music's animated artist artwork, cached per YouTube channel.
 *
 * A row means "this artist has been asked about", **not** "this artist has a video": AM carries
 * motion for roughly three artists in five, so without a negative cache the two requests behind it
 * would run again on every visit to the page of an artist that has none.
 *
 * The urls stored are already resolved down to a single rendition, the way the album path stores
 * its own in `song.canvasUrl` — so neither player has to pick from the ladder, and the two
 * platforms cannot disagree about which one to play.
 */
@Entity(tableName = "artist_motion")
data class ArtistMotionEntity(
    @PrimaryKey(autoGenerate = false)
    val channelId: String,
    /** `motionArtistSquare1x1` — the square header a portrait window draws. */
    val squareVideoUrl: String? = null,
    val squareThumbUrl: String? = null,
    /** `motionArtistWide16x9` — the banner-shaped header a landscape window draws. */
    val wideVideoUrl: String? = null,
    val wideThumbUrl: String? = null,
    /** Epoch millis of the lookup, so an artist AM has animated since is eventually asked again. */
    val fetchedAt: Long,
)
