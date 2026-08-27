package com.maxrave.domain.data.type

/**
 * One month's recap, as the Library grid sees it.
 *
 * A [PlaylistType] because the Wrapped tab draws its recaps through the same `GridLibraryPlaylist`
 * every other playlist list in Library uses, and that component renders only what implements this
 * marker — a parallel row model would mean a second, divergent way of drawing a playlist tile.
 *
 * [year] and [month] are the identity; everything the tile prints is resolved before it gets here.
 * [title] is a resolved string rather than a `StringResource`, because "Recap January" is a format
 * string plus a month name and a resource reference cannot carry the argument.
 *
 * It carries NO artwork on purpose. A recap is a playlist, so it gets the tile every artwork-less
 * playlist in this app gets: the deterministic gradient with its own name written on it, drawn by
 * `painterPlaylistThumbnail`, exactly as a local playlist does. It briefly borrowed the month's
 * top song's cover instead, which made the tile read as that song rather than as a recap.
 *
 * [month] is 1..12, matching `kotlinx.datetime.Month.number`, so it round-trips into the
 * navigation argument without an enum lookup.
 */
data class MonthlyRecapItem(
    val year: Int,
    val month: Int,
    val title: String,
) : PlaylistType {
    /**
     * Assembled on this device out of this device's own listening history — nothing about it comes
     * from YouTube, and it is not an album, a radio or a podcast. [PlaylistType.Type.LOCAL] is the
     * only honest answer left, and it is also the one that makes the full-width row (the one place
     * this is read) print "Playlist".
     */
    override fun playlistType(): PlaylistType.Type = PlaylistType.Type.LOCAL
}
