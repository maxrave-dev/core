package com.maxrave.media3.cache

import android.content.Context
import android.net.ConnectivityManager
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.cache.SimpleCache
import com.maxrave.domain.data.player.LiveStreamRegistry
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.logger.Logger
import com.maxrave.media3.extension.isFullyCached
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Prevention layer for mid-song stream stalls: once a track has been playing for a short grace
 * period, pull the WHOLE track into the player cache through the player's own resolving,
 * cache-backed chain (the same read pattern [com.maxrave.media3.waveform.WaveformReader] uses for
 * its offline analysis). With every byte local, a network drop mid-song has nothing left to fetch.
 *
 * A watchdog loop supervises the download: a pass that fails (dead connection, expired URL) is
 * retried with backoff until the track is fully cached. Already-cached spans are served from the
 * cache on the next pass, so a retry only fetches the holes that are still missing. When the
 * current track is fully local, the next track in the queue is cached the same way.
 *
 * Deliberate limits: a track skipped inside the grace period costs no full download; a new track
 * cancels the previous track's job; live broadcasts are never cached (they have no end); and the
 * user can restrict full-track caching to unmetered networks (Wi-Fi) from settings.
 */
@OptIn(UnstableApi::class)
internal class TrackCachePreloader(
    private val context: Context,
    private val dataSourceFactory: DataSource.Factory,
    private val downloadCache: SimpleCache,
    private val playerCache: SimpleCache,
    private val streamRepository: StreamRepository,
    private val dataStoreManager: DataStoreManager,
    private val coroutineScope: CoroutineScope,
) {
    private var watchdogJob: Job? = null
    private var activeMediaId: String? = null

    /** Called when a track becomes the current one. Restarts the watchdog for the new track. */
    fun onTrackStarted(
        mediaId: String,
        nextMediaId: String?,
    ) {
        if (mediaId == activeMediaId && watchdogJob?.isActive == true) return
        activeMediaId = mediaId
        watchdogJob?.cancel()
        watchdogJob = null
        if (mediaId.isBlank() || LiveStreamRegistry.isLive(mediaId)) return
        watchdogJob =
            coroutineScope.launch {
                // Grace period first: most skips happen in the first seconds of a track, and a
                // skipped track should not cost a full download.
                delay(GRACE_PERIOD_MS)
                if (dataStoreManager.fullTrackCacheWifiOnly.firstOrNull() == DataStoreManager.TRUE && isMetered()) {
                    Logger.d(TAG, "Metered network and Wi-Fi-only caching is on: skipping full-track cache for $mediaId")
                    return@launch
                }
                cacheWithWatchdog(mediaId)
                // Only once the current track is fully local: prefetch the next one, so it is
                // already on the device when its turn comes.
                if (nextMediaId != null && nextMediaId != mediaId && !LiveStreamRegistry.isLive(nextMediaId)) {
                    cacheWithWatchdog(nextMediaId)
                }
            }
    }

    /** Stops any in-flight caching (e.g. the player is being torn down). */
    fun cancel() {
        watchdogJob?.cancel()
        watchdogJob = null
        activeMediaId = null
    }

    /**
     * The watchdog: repeat whole-track passes until one completes. A pass over a dead connection
     * fails out through the HTTP client's timeouts; the pause between attempts grows so a long
     * outage is retried persistently without hammering.
     */
    private suspend fun cacheWithWatchdog(mediaId: String) {
        var attempt = 0
        while (attempt < MAX_ATTEMPTS) {
            attempt++
            val outcome =
                try {
                    downloadWholeTrack(mediaId)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "Full-track cache pass $attempt for $mediaId failed: ${e.message}")
                    PassOutcome.RETRY
                }
            when (outcome) {
                PassOutcome.COMPLETE -> {
                    Logger.d(TAG, "Track fully cached: $mediaId")
                    return
                }
                // Retrying cannot fix a missing content length: stop instead of looping 8 times.
                PassOutcome.GIVE_UP -> return
                PassOutcome.RETRY -> Unit
            }
            if (attempt < MAX_ATTEMPTS) {
                delay(RETRY_BASE_DELAY_MS * (1 shl (attempt - 1)).coerceAtMost(RETRY_BACKOFF_CAP))
            }
        }
        Logger.w(TAG, "Full-track cache gave up on $mediaId after $MAX_ATTEMPTS attempts")
    }

    /**
     * Reads the whole track through the resolving chain, discarding the bytes: every byte read
     * lands in the player cache, exactly as it does when the player itself streams it. Reopens at
     * resolver chunk boundaries, like WaveformReader's analysis download. Returns true only when
     * the cache provably holds the track end to end.
     */
    private suspend fun downloadWholeTrack(mediaId: String): PassOutcome =
        withContext(Dispatchers.IO) {
            if (downloadCache.isFullyCached(mediaId, 0L)) return@withContext PassOutcome.COMPLETE
            val storedLength =
                streamRepository
                    .getNewFormat(mediaId)
                    .firstOrNull()
                    ?.contentLength
            val length = storedLength?.takeIf { it in 1..MAX_AUDIO_BYTES }
            if (length == null) {
                // Unknown or oversized length is a permanent condition for this track.
                Logger.w(TAG, "Skipping full-track cache for $mediaId: unusable content length ($storedLength)")
                return@withContext PassOutcome.GIVE_UP
            }
            if (playerCache.isCached(mediaId, 0, length)) return@withContext PassOutcome.COMPLETE
            val buffer = ByteArray(64 * 1024)
            var position = 0L
            while (position < length) {
                currentCoroutineContext().ensureActive()
                val source = dataSourceFactory.createDataSource()
                var readThisOpen = 0L
                try {
                    source.open(
                        DataSpec
                            .Builder()
                            .setUri(mediaId)
                            .setKey(mediaId)
                            .setPosition(position)
                            .build(),
                    )
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = source.read(buffer, 0, buffer.size)
                        if (read == C.RESULT_END_OF_INPUT) break
                        readThisOpen += read
                    }
                } finally {
                    source.close()
                }
                if (readThisOpen == 0L) {
                    Logger.w(TAG, "Full-track cache pass read 0 bytes at $position/$length for $mediaId")
                    return@withContext PassOutcome.RETRY
                }
                position += readThisOpen
            }
            if (playerCache.isCached(mediaId, 0, length)) {
                PassOutcome.COMPLETE
            } else {
                Logger.w(TAG, "Full-track cache pass finished but $mediaId is not fully cached ($length bytes expected)")
                PassOutcome.RETRY
            }
        }

    private enum class PassOutcome { COMPLETE, RETRY, GIVE_UP }

    private fun isMetered(): Boolean {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return connectivityManager.isActiveNetworkMetered
    }

    private companion object {
        const val TAG = "TrackCachePreloader"

        /** A track must survive this long before it earns a full download. */
        const val GRACE_PERIOD_MS = 25_000L

        /** First pause between watchdog passes; doubles per attempt, capped by [RETRY_BACKOFF_CAP]. */
        const val RETRY_BASE_DELAY_MS = 5_000L
        const val RETRY_BACKOFF_CAP = 6
        const val MAX_ATTEMPTS = 8

        /** Same sanity cap WaveformReader applies to a track's audio. */
        const val MAX_AUDIO_BYTES = 64L * 1024 * 1024
    }
}
