package com.maxrave.domain.mediaservice.automix

/**
 * Where an AutoMix fade happens, in each track's own milliseconds: the outgoing track's window and
 * the second the incoming track plays from.
 */
data class TransitionPlan(
    /** The outgoing track's position when the blend starts. */
    val outgoingStartMs: Long,
    /** The outgoing track's position when the blend ends: its music's end, later only to spare its last drop. */
    val outgoingEndMs: Long,
    /** The fade's length: the adapters' own formula, shortened only by a late start. */
    val durationMs: Long,
    /** Where the incoming track starts playing. */
    val incomingStartMs: Long,
) {
    /**
     * The same plan for a blend that starts with the outgoing track already [outgoingPositionMs] in:
     * a late start keeps the drop on the phrase end by starting the incoming track that much later
     * and shortening the blend. Null when that leaves under a second, or when the position is not
     * near the window at all (a seek, the end-of-file path); the caller then fades the old way.
     */
    fun startingAt(outgoingPositionMs: Long): TransitionPlan? {
        if (outgoingPositionMs < outgoingStartMs - TOLERANCE_MS) return null
        val late = (outgoingPositionMs - outgoingStartMs).coerceAtLeast(0)
        if (durationMs - late < MIN_DURATION_MS) return null
        return copy(outgoingStartMs = outgoingStartMs + late, durationMs = durationMs - late, incomingStartMs = incomingStartMs + late)
    }

    private companion object {
        const val TOLERANCE_MS = 1_000L
        const val MIN_DURATION_MS = 1_000L
    }
}

/**
 * Decides only WHERE an AutoMix fade sits, from the parts the waveform's loudness tells apart: the
 * second the outgoing track starts and ends it, and the second the incoming track plays from. How long it lasts (the adapters' tempo/key
 * formula) and what it does (filters, volume, tempo and pitch ramp) stay exactly as they were.
 */
object TransitionPlanner {
    // Mirrors BPM_RAMP_PORTION in both adapters: the outgoing tempo reaches its target within this
    // share of the blend on a smoothstep, then holds.
    private const val RAMP_PORTION = 0.6

    /**
     * The outgoing song is mixed out over its outro, as DJs do: the fade ends where its music ends. It
     * never starts before the middle of the last drop, so the first half of that drop is always heard
     * clean; when the outro is too short to hold the fade, the fade starts there and runs on into the
     * quieter tail. It never runs past the last sound either, or the outgoing track would stop while
     * still audible, so a song that ends right on its drop still fades over part of it. The incoming
     * track plays from where the end of its intro lands on the fade's end; from 0 when its intro is
     * shorter than the fade, or not told apart.
     *
     * @param durationMs the length the adapters' tempo/key formula gives this pair, used as is.
     * @param outgoingSpeedRatio the speed the outgoing track ramps to over the fade (1.0 = none).
     * @return null when the outgoing song is too short to hold the fade.
     */
    fun plan(
        outgoing: TrackStructure,
        incoming: TrackStructure,
        durationMs: Long,
        outgoingSpeedRatio: Float,
    ): TransitionPlan? {
        // The outgoing track covers more (or less) of its own timeline than the fade lasts, because
        // its speed ramps: smoothstep over RAMP_PORTION averages half way, then holds the target.
        val outgoingAdvance = (durationMs * (1 + (outgoingSpeedRatio - 1) * (1 - RAMP_PORTION / 2))).toLong()
        var start = outgoing.musicEndMs - outgoingAdvance
        outgoing.lastChorusStartMs?.let { dropStart ->
            val dropEnd = outgoing.lastChorusEndMs ?: outgoing.musicEndMs
            start = maxOf(start, dropStart + (dropEnd - dropStart) / 2)
        }
        start = minOf(start, outgoing.lastSoundMs - outgoingAdvance)
        if (start <= outgoing.firstSoundMs) return null
        return TransitionPlan(
            outgoingStartMs = start,
            outgoingEndMs = start + outgoingAdvance,
            durationMs = durationMs,
            incomingStartMs = incoming.introEndMs?.let { (it - durationMs).coerceAtLeast(0) } ?: 0L,
        )
    }
}
