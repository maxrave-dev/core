package com.maxrave.domain.lyrics

import com.maxrave.domain.data.model.metadata.Lyrics

enum class LyricsSource {
    SIMPMUSIC,
    YOUTUBE,
    SPOTIFY,
    LRCLIB,
    BETTER_LYRICS,
    OFFLINE,
}

data class LyricsLookupRequest(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationSeconds: Int?,
    val preferredSource: LyricsSource,
    val allowSpotify: Boolean,
    val preferCache: Boolean = true,
    val allowUpgradeScan: Boolean = true,
    val userProtectedSignatures: Set<Int> = emptySet(),
)

data class LyricsResolution(
    val videoId: String,
    val lyrics: Lyrics,
    val source: LyricsSource,
    val fromCache: Boolean,
    val youtubeTranslatedLyrics: Lyrics? = null,
    val simpMusicNotFoundObserved: Boolean = false,
    val isUserProtected: Boolean = false,
)

sealed interface LyricsResolutionEvent {
    data class Found(
        val resolution: LyricsResolution,
    ) : LyricsResolutionEvent

    data class Exhausted(
        val videoId: String,
        val definitiveNotFound: Boolean,
        val confirmedInstrumental: Boolean = false,
    ) : LyricsResolutionEvent
}

sealed interface ProviderFetchOutcome {
    data class Success(
        val lyrics: Lyrics,
        val translatedLyrics: Lyrics? = null,
    ) : ProviderFetchOutcome

    data object DefinitiveNotFound : ProviderFetchOutcome

    data object ConfirmedInstrumental : ProviderFetchOutcome

    data class TransientFailure(
        val reason: String? = null,
    ) : ProviderFetchOutcome
}

object LyricsQuality {
    const val LEVEL_INVALID = 0
    const val LEVEL_UNSYNCED = 1
    const val LEVEL_LINE_SYNCED = 2
    const val LEVEL_RICH_SYNCED = 3

    private val RICH_TAG_REGEX = Regex("""<\d{1,3}:\d{2}(?:[.:]\d{1,3})?>""")

    fun level(lyrics: Lyrics?): Int {
        val lines = lyrics?.lines ?: return LEVEL_INVALID
        if (lyrics.error || lines.isEmpty() || lines.all { it.words.isBlank() }) {
            return LEVEL_INVALID
        }
        if (!LyricsContentQualityGuard.isAcceptableContent(lyrics)) {
            return LEVEL_INVALID
        }
        return when (lyrics.syncType) {
            "RICH_SYNCED" ->
                if (lines.any { RICH_TAG_REGEX.containsMatchIn(it.words) }) LEVEL_RICH_SYNCED else LEVEL_LINE_SYNCED
            "LINE_SYNCED" -> {
                val distinctTimestamps = lines.mapNotNull { it.startTimeMs.toLongOrNull() }.distinct().size
                if (distinctTimestamps > 1 || (lines.size == 1 && (lines[0].startTimeMs.toLongOrNull() ?: 0L) > 0L)) {
                    LEVEL_LINE_SYNCED
                } else {
                    LEVEL_UNSYNCED
                }
            }
            else -> LEVEL_UNSYNCED
        }
    }

    fun isStrictUpgrade(
        candidate: Lyrics?,
        current: Lyrics?,
    ): Boolean = level(candidate) > level(current)

    fun contentSignature(lyrics: Lyrics): Int {
        var result = lyrics.syncType.hashCode()
        lyrics.lines.orEmpty().forEach { line ->
            result = 31 * result + line.startTimeMs.hashCode()
            result = 31 * result + line.endTimeMs.hashCode()
            result = 31 * result + line.words.hashCode()
        }
        return result
    }
}
