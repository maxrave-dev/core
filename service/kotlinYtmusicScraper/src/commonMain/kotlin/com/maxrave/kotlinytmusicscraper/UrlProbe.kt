package com.maxrave.kotlinytmusicscraper

/**
 * One ranged GET against [url], returning the HTTP status, or null when this platform
 * cannot probe. Exists only for the fast path's shadow diagnosis: whether a URL the
 * fast attempt assembled is alive (206) or dead on arrival (403). Never used on the
 * playback path — playback URLs are trusted until the player itself reports them dead.
 */
internal expect suspend fun probeHttpStatus(url: String): Int?
