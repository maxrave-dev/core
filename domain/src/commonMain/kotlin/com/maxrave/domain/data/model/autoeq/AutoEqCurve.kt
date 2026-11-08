package com.maxrave.domain.data.model.autoeq

/**
 * A correction curve resolved from an AutoEq profile, ready to hand to the equalizer.
 *
 * [bandsDb] holds ten gains in the band order the equalizer already uses. AutoEq generates its
 * fixed-band output at the same ISO octave centres, the same Q, and within the same ±12 dB, so
 * nothing is interpolated or clamped on the way in — see the parser for the citation.
 */
data class AutoEqCurve(
    val bandsDb: List<Float>,
    val preampDb: Float,
)
