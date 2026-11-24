package com.maxrave.domain.mediaservice.automix

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The shape of a track that AutoMix places a transition by, measured from the track's own audio
 * by [TrackStructureAnalyzer]. Every time is in milliseconds of the track's own timeline.
 */
@Serializable
data class TrackStructure(
    val version: Int = VERSION,
    val bpm: Double,
    val beatMs: Double,
    /** The first beat of every bar, in order. */
    val downbeatsMs: List<Long>,
    /** Bars where the loudness of the next four bars differs from the four before by more than 3 dB. */
    val sections: List<SectionChange>,
    /** First and last moment above -60 dBFS (Mixxx's silence threshold). */
    val firstSoundMs: Long,
    val lastSoundMs: Long,
    /**
     * The bar line closing the last bar still played near the track's usual loudness (within 10 dB
     * of the median bar): where the song's last phrase ends, before any tail or fade-out.
     */
    val musicEndMs: Long,
    /**
     * Where the intro ends, labelled by loudness: the first part when it is at least 3 dB quieter than
     * both parts after it. Null when loudness does not set an intro apart.
     */
    val introEndMs: Long? = null,
    /**
     * Where the last chorus (the last run of parts within 2 dB of the loudest: in dance music, the
     * last drop) starts. Null when loudness does not separate the parts.
     */
    val lastChorusStartMs: Long? = null,
    /**
     * Where that last chorus ends, when a quieter part (an outro) follows it, however long. Null when
     * the song ends on its chorus or loudness does not separate the parts.
     */
    val lastChorusEndMs: Long? = null,
    /**
     * Where the second half of the last drop starts: the first beat after the rest (kick and bass gone
     * for a beat or more) that splits the drop in two. Null when no rest is found in the drop.
     */
    val lastDropSecondHalfMs: Long? = null,
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        /** Bump whenever the analysis changes, so results stored by an older build are measured again. */
        const val VERSION = 4

        private val json =
            Json {
                encodeDefaults = true
                ignoreUnknownKeys = true
            }

        /** Null for an empty file (a track measured before and found unusable) or an older version. */
        fun decode(text: String): TrackStructure? =
            runCatching { json.decodeFromString(serializer(), text) }
                .getOrNull()
                ?.takeIf { it.version == VERSION }
    }
}

@Serializable
data class SectionChange(
    val timeMs: Long,
    /** Loudness of the four bars after minus the four bars before; positive is a rise (a drop). */
    val deltaDb: Double,
)
