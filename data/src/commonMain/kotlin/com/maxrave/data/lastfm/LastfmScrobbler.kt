package com.maxrave.data.lastfm

import com.maxrave.domain.data.entities.SongEntity
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.logger.Logger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.simpmusic.lastfm.LastfmOutcome
import org.simpmusic.lastfm.LastfmTrack
import org.simpmusic.lastfm.isLastfmAvailable
import org.simpmusic.lastfm.scrobble
import org.simpmusic.lastfm.updateNowPlaying
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

private const val TAG = "LastfmScrobbler"

/**
 * Decides when a play becomes a scrobble, and remembers what has already been sent.
 *
 * This lives in `commonMain` rather than in either player handler because Android and Desktop run
 * completely separate handlers — the Discord integration is duplicated across both, and duplicating
 * the scrobble rules too would mean two places to get the thresholds wrong.
 */
class LastfmScrobbler(
    private val dataStoreManager: DataStoreManager,
) {
    /** Below this a track is never scrobbled, per Last.fm's rules. */
    private val minimumTrackSeconds = 30

    /** A play this long counts even if the track is much longer. */
    private val scrobbleAfterSeconds = 240

    private val mutex = Mutex()

    private var currentVideoId: String? = null
    private var currentTrack: LastfmTrack? = null
    private var currentDurationSeconds: Int = 0
    private var startedAtEpochSeconds: Long = 0
    private var scrobbled: Boolean = false

    private suspend fun sessionKeyOrNull(): String? {
        if (!isLastfmAvailable()) return null
        if (dataStoreManager.lastfmScrobbleEnabled.first() != DataStoreManager.TRUE) return null
        return dataStoreManager.lastfmSessionKey.first().takeIf { it.isNotEmpty() }
    }

    /**
     * Called when playback moves to a track.
     *
     * The timestamp is taken here, at the start of the play, because that is what `track.scrobble`
     * expects — see the note on [scrobble] about the two contradictory pages in Last.fm's docs.
     */
    @OptIn(ExperimentalTime::class)
    suspend fun onTrackStarted(song: SongEntity) {
        mutex.withLock {
            if (currentVideoId == song.videoId) return
            currentVideoId = song.videoId
            currentDurationSeconds = song.durationSeconds
            startedAtEpochSeconds = Clock.System.now().epochSeconds
            scrobbled = false
            currentTrack = song.toLastfmTrack()
        }

        val sessionKey = sessionKeyOrNull() ?: return
        val track = currentTrack ?: return
        when (val outcome = updateNowPlaying(sessionKey, track)) {
            is LastfmOutcome.Ok -> Logger.d(TAG, "Now playing: ${track.artist} - ${track.track}")
            is LastfmOutcome.Ignored ->
                Logger.w(TAG, "Now playing ignored (${outcome.code}): ${outcome.message}")
            is LastfmOutcome.Error -> handleError(outcome, "track.updateNowPlaying")
        }
    }

    /**
     * Called as playback advances.
     *
     * Cheap to call on every progress tick: once [scrobbled] is set the whole thing is a field read,
     * and the threshold check happens before any suspending work.
     */
    suspend fun onProgress(positionMs: Long) {
        val track: LastfmTrack
        mutex.withLock {
            if (scrobbled) return
            if (currentDurationSeconds <= minimumTrackSeconds) return
            val threshold = minOf(currentDurationSeconds / 2, scrobbleAfterSeconds)
            if (positionMs / 1000 < threshold) return
            // Claim the scrobble before suspending so a second progress tick cannot send it twice.
            scrobbled = true
            track = currentTrack ?: return
        }

        val sessionKey = sessionKeyOrNull() ?: return
        when (
            val outcome =
                scrobble(
                    sessionKey = sessionKey,
                    track = track,
                    startedAtEpochSeconds = startedAtEpochSeconds,
                )
        ) {
            is LastfmOutcome.Ok -> Logger.d(TAG, "Scrobbled: ${track.artist} - ${track.track}")
            is LastfmOutcome.Ignored ->
                // Codes 1 and 2 mean Last.fm filtered the artist or track name, which is how bad
                // metadata surfaces — worth a loud log rather than a silent drop.
                Logger.w(TAG, "Scrobble ignored (${outcome.code}): ${outcome.message}")
            is LastfmOutcome.Error -> handleError(outcome, "track.scrobble")
        }
    }

    /** Forgets the current track, so the next play of the same song scrobbles again. */
    suspend fun reset() {
        mutex.withLock {
            currentVideoId = null
            currentTrack = null
            currentDurationSeconds = 0
            scrobbled = false
        }
    }

    /**
     * A dead session key is the one error worth acting on: the user revoked access on last.fm, and
     * every later call would fail the same way. Clearing it puts the settings entry back to "log
     * in" instead of silently doing nothing forever.
     */
    private suspend fun handleError(
        error: LastfmOutcome.Error,
        method: String,
    ) {
        Logger.e(TAG, "$method failed (${error.code}): ${error.message}")
        if (error.needsReauth) {
            dataStoreManager.setLastfmSession(sessionKey = "", username = "")
        }
    }
}

/**
 * Last.fm takes a single artist name, not SimpMusic's list.
 *
 * The first entry is the primary artist — and since the parser now keeps only the first group of
 * the subtitle column, it is an artist rather than an album name or a view count.
 */
private fun SongEntity.toLastfmTrack(): LastfmTrack =
    LastfmTrack(
        artist = artistName?.firstOrNull().orEmpty(),
        track = title,
        album = albumName,
        albumArtist = artistName?.firstOrNull(),
        durationSeconds = durationSeconds,
    )
