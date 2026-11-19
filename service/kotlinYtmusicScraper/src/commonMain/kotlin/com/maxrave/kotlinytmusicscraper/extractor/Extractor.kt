package com.maxrave.kotlinytmusicscraper.extractor

import com.maxrave.kotlinytmusicscraper.models.SongItem
import com.maxrave.kotlinytmusicscraper.models.response.DownloadProgress

expect class Extractor() {
    fun init()

    fun logIn(cookie: String?)

    fun mergeAudioVideoDownload(filePath: String): DownloadProgress

    fun saveAudioWithThumbnail(
        filePath: String,
        track: SongItem,
    ): DownloadProgress

    fun newPipePlayer(videoId: String): List<Pair<Int, String>>

    /**
     * Decipher ONLY the given raw `signatureCipher`s with the platform's local decoder and
     * assemble their stream URLs, without running an extraction — the fast path a streaming
     * resolve takes when it already knows which formats it is going to pick.
     *
     * Returns a map from each input cipher to its assembled URL holding ONLY the ciphers that
     * were solved completely; a cipher missing from the map failed, and an empty map means the
     * fast path is unavailable (no local decoder on this platform, no player metadata, nothing
     * solved), so the caller falls back to [newPipePlayer] exactly as before.
     */
    suspend fun decipherUrls(ciphers: List<String>): Map<String, String>

    /**
     * The HLS playlist of a video that is broadcasting live right now, or null when no extractor
     * produced one.
     *
     * Kept apart from [newPipePlayer] on purpose: that path is built for videos of a fixed length,
     * and every tier there rejects a result without the regular audio and video itags — which a
     * live stream never carries, so a perfectly good live extraction looked like a failed one.
     */
    fun liveHlsUrl(videoId: String): String?

    /**
     * Pay the local decoder's one-time setup (current player id, remote player table,
     * player script, QuickJS solver) ahead of the first extraction that needs it, so the
     * first play does not have to. Platforms without a local decoder do nothing.
     */
    fun warmUp()
}