package com.maxrave.domain.repository

import com.maxrave.domain.data.model.taste.TasteInput
import com.maxrave.domain.data.model.taste.TasteProfile
import kotlinx.coroutines.flow.Flow

/** The AI reading of the listener's taste behind the Library card. */
interface TasteRepository {
    /** The last reading, or null before the first one and after the listening history is cleared. */
    val tasteProfile: Flow<TasteProfile?>

    /**
     * Asks the configured AI provider for a reading and keeps it.
     *
     * Fails with [com.maxrave.domain.data.model.taste.TasteException]. A failure leaves the
     * previous reading stored, so a refused "Regenerate" does not take the card away.
     */
    suspend fun describeTaste(
        input: TasteInput,
        artistImages: List<String>,
    ): Result<TasteProfile>
}
