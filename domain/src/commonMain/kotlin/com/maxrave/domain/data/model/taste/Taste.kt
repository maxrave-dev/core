package com.maxrave.domain.data.model.taste

import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.Serializable

/**
 * What the app hands the AI to read a listener's taste from: their own history, read and cleaned
 * on the device. The AI has no other source — every artist and song it can talk about is in here.
 *
 * Behaviour travels as hints ([dayParts], [replay], [concentration]) rather than as figures for the
 * reading to quote. The card is about taste; the numbers already live on the Analytics screen.
 */
data class TasteInput(
    /** BCP-47 tag of the app language, which the reading is written in. */
    val language: String,
    /** False in the first year on record, where every artist looks "new" and discovery means nothing. */
    val hasEarlierHistory: Boolean,
    /** Most played first. */
    val topArtists: List<String>,
    /** Most played first. */
    val topSongs: List<TasteSong>,
    /** Most played first. */
    val topAlbums: List<TasteAlbum>,
    /** Share of plays at night, in the morning, afternoon and evening — six-hour bands from midnight, 0..1. */
    val dayParts: List<Float>,
    /** [com.maxrave.domain.data.model.analytics.ListeningFingerprint.replay]. */
    val replay: Float,
    /** [com.maxrave.domain.data.model.analytics.ListeningFingerprint.concentration]. */
    val concentration: Float,
    /** Most played artists of the twelve months before; empty without earlier history. */
    val previousTopArtists: List<String>,
)

data class TasteSong(
    val title: String,
    val artists: String,
)

data class TasteAlbum(
    val title: String,
    val artists: String,
    val year: String?,
)

/** The AI's reading, as it comes back. */
data class TasteReading(
    val headline: String,
    val summary: String,
)

/**
 * A reading kept on the device: what the Library card and its share image draw.
 *
 * [artistImages] are the top artists' pictures at the time of the reading, so the card does not
 * change faces under a text written about other ones.
 */
@Serializable
data class TasteProfile(
    val headline: String,
    val summary: String,
    val artistImages: List<String> = emptyList(),
    val generatedAt: LocalDateTime,
)

/** Why a reading could not be made, sorted by what the user can do about it. */
class TasteException(
    val kind: Kind,
    val statusCode: Int? = null,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Kind {
        /** No API key at all. */
        NOT_CONFIGURED,

        /** The provider refused the key or the model (400, 401, 403, 404): fixed in Settings. */
        REJECTED,

        /** Quota, server trouble, a timeout, an unreadable answer: worth trying again. */
        TEMPORARY,
    }
}
