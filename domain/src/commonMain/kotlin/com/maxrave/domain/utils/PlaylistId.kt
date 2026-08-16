package com.maxrave.domain.utils

/**
 * Prefixes of YouTube Music playlist ids that are really radios: YouTube keeps generating tracks
 * for them and never stops handing out a continuation token, so their track list has no end.
 *
 * - `RDAMVM` — radio seeded from a video
 * - `RDEM`   — radio seeded from an endpoint
 * - `RDAT`   — artist radio
 * - `RDTM`   — personalised mixes such as Replay Mix, Supermix and Discover Mix (`RDTMAK5uy_…`)
 *
 * Deliberately not the bare `RD` prefix: `RDCLAK5uy_…` is a curated YouTube Music playlist with a
 * finite track list, and calling it a radio would hide its download button.
 */
private val RADIO_PLAYLIST_ID_PREFIXES = listOf("RDAMVM", "RDEM", "RDAT", "RDTM")

private val RADIO_MIX_ID_PREFIXES = listOf("RDAT", "RDTM")

/**
 * Whether this playlist id belongs to a radio. Anything paging through such a playlist must stop
 * on its own terms — waiting for YouTube to run out of continuations never happens.
 */
fun String.isRadioPlaylistId(): Boolean = RADIO_PLAYLIST_ID_PREFIXES.any { startsWith(it) }

/**
 * Whether this id is a queue YouTube put together rather than one the user picked: any radio, and
 * the curated mixes too.
 *
 * Deliberately the bare `RD` prefix, which is wider than [isRadioPlaylistId] on purpose — that one
 * excludes `RDCLAK5uy_…` so a curated mix keeps its download button, but for deciding whether a
 * queue was auto-generated a mix counts exactly like a radio.
 *
 * The `VL` is stripped first because a queue's `playlistId` is the id YouTube plays from and never
 * wears that prefix, while the same radio stored in the library is browsed as `VLRDAMVM…`.
 */
fun String.isRadioQueueId(): Boolean = removePrefix("VL").startsWith("RD")

fun String.isRadioMix(): Boolean = RADIO_MIX_ID_PREFIXES.any { startsWith(it) }