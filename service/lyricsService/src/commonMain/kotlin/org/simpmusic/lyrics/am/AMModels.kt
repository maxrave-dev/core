package org.simpmusic.lyrics.am

import kotlinx.serialization.Serializable

/**
 * Response shape for the AM catalog search (format[resources]=map): a flat [resources] map keyed
 * by id, plus a [results] block that preserves the search ranking order.
 */
@Serializable
data class AMSearchResponse(
    val results: AMResults? = null,
    val resources: AMResources? = null,
)

@Serializable
data class AMResults(
    val artists: AMRefList? = null,
    val albums: AMRefList? = null,
    val songs: AMRefList? = null,
)

@Serializable
data class AMRefList(
    val data: List<AMRef> = emptyList(),
)

@Serializable
data class AMRef(
    val id: String,
)

@Serializable
data class AMResources(
    val artists: Map<String, AMArtistResource> = emptyMap(),
    val albums: Map<String, AMAlbumResource> = emptyMap(),
    val songs: Map<String, AMSongResource> = emptyMap(),
)

@Serializable
data class AMArtistResource(
    val id: String,
    val attributes: AMArtistAttributes? = null,
)

@Serializable
data class AMArtistAttributes(
    val name: String? = null,
    val url: String? = null,
    // Hex string (no `#`); present on the artist-detail endpoint.
    val keyColor: String? = null,
    val artwork: AMArtwork? = null,
    // Present on the artist-detail endpoint (extend=editorialArtwork); null on plain search.
    val editorialArtwork: AMEditorialArtwork? = null,
)

/**
 * Editorial art variants. [musicContentColorLogoTrimmed] is the artist NAME rendered as a trimmed
 * color logo image (PNG) — the header title art shown in place of plain text.
 */
@Serializable
data class AMEditorialArtwork(
    val musicContentColorLogoTrimmed: AMArtwork? = null,
    val subscriptionHero: AMArtwork? = null,
    val bannerUber: AMArtwork? = null,
)

/**
 * Artwork descriptor. [url] is a template containing `{w}`, `{h}` and `{f}` placeholders that must
 * be substituted before requesting the actual image. The `*Color` fields are hex strings (no `#`).
 */
@Serializable
data class AMArtwork(
    val url: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val bgColor: String? = null,
    val textColor1: String? = null,
    val textColor2: String? = null,
    val textColor3: String? = null,
    val textColor4: String? = null,
)

/**
 * Substitute the `{w}`, `{h}`, `{c}` (crop) and `{f}` (format) placeholders in [AMArtwork.url].
 * Returns null when there is no url template.
 */
fun AMArtwork.toImageUrl(
    width: Int,
    height: Int,
    format: String = "png",
    crop: String = "",
): String? =
    url
        ?.replace("{w}", width.toString())
        ?.replace("{h}", height.toString())
        ?.replace("{c}", crop)
        ?.replace("{f}", format)

/**
 * Album resource from the AM catalog search (`types=albums`). The animated artwork rides on
 * [AMAlbumAttributes.editorialVideo], which is only populated when the request asks for
 * `extend=editorialVideo`.
 */
@Serializable
data class AMAlbumResource(
    val id: String,
    val attributes: AMAlbumAttributes? = null,
)

@Serializable
data class AMAlbumAttributes(
    val name: String? = null,
    val artistName: String? = null,
    val url: String? = null,
    val artwork: AMArtwork? = null,
    // Present only with extend=editorialVideo; null for albums that have no animated artwork.
    val editorialVideo: AMEditorialVideo? = null,
)

/**
 * The animated album artwork variants. Roughly two thirds of albums carry none at all, and an album
 * that has one always ships all four. `Detail*` are the ones the Apple Music app itself shows on an
 * album page; the `motion*Video*` pair is the same footage cut for other surfaces.
 */
@Serializable
data class AMEditorialVideo(
    val motionDetailSquare: AMMotionVideo? = null,
    val motionDetailTall: AMMotionVideo? = null,
    val motionSquareVideo1x1: AMMotionVideo? = null,
    val motionTallVideo3x4: AMMotionVideo? = null,
)

/**
 * One animated artwork rendition. [video] is an HLS master playlist (`.m3u8`) served without any
 * auth and with no DRM, so a plain player can read it; [previewFrame] is the still shown before the
 * first frame arrives, and its url is a `{w}x{h}` template like every other [AMArtwork].
 */
@Serializable
data class AMMotionVideo(
    val video: String? = null,
    val previewFrame: AMArtwork? = null,
)

/**
 * A track from the catalog search. Only reached through `types=songs`, and only for the album it
 * belongs to: [relationships] is the whole point of asking for songs at all.
 */
@Serializable
data class AMSongResource(
    val id: String,
    val attributes: AMSongAttributes? = null,
    val relationships: AMSongRelationships? = null,
)

@Serializable
data class AMSongAttributes(
    val name: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val durationInMillis: Long? = null,
)

@Serializable
data class AMSongRelationships(
    val albums: AMRefList? = null,
)

/**
 * A track paired with the album it actually appears on.
 *
 * The pairing has to be carried explicitly. A `types=songs` response returns every album any hit
 * belongs to in one flat map, so picking "the first album with artwork" out of that map attaches an
 * unrelated release to the track being played — for an artist with a single animated release, every
 * one of their songs ends up wearing it.
 */
data class AMSongWithAlbum(
    val songName: String?,
    val artistName: String?,
    val durationInMillis: Long?,
    val album: AMAlbumResource,
)
