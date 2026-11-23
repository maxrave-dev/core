package com.maxrave.domain.mediaservice.player

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/** [BassEnvelope] keeps one level per 1 / [BASS_SLOTS_PER_SECOND] s of a track. */
const val BASS_SLOTS_PER_SECOND = 40

/**
 * How hard a track's bass hits over time, measured from its own decoded audio, for the lyrics background's
 * pulse. Mono samples go through two one-pole low-passes at [CUTOFF_HZ], which leaves the kick and the
 * bass line, and each slot keeps the RMS of what passed. [pulseAt] answers against the hits around it
 * rather than full scale, so a quiet master, and a quiet verse, pulse as much as a loud drop.
 *
 * Not thread-safe: Android feeds and reads it under its waveform store's lock, and Desktop builds one
 * completely before anything reads it.
 */
class BassEnvelope {
    private var levels = FloatArray(0)
    private val histogram = IntArray(HISTOGRAM_BINS)
    private var histogramCount = 0
    private var reference = 0f

    // Writer side: the filter and the slot being filled.
    private var sampleRate = 0
    private var alpha = 0f
    private var low1 = 0f
    private var low2 = 0f
    private var lastTimeUs = Long.MIN_VALUE
    private var slot = -1
    private var sumSquares = 0.0
    private var count = 0

    /** One mono sample, -1..1, heard at [timeUs] into the track. */
    fun add(
        timeUs: Long,
        sample: Float,
        sampleRate: Int,
    ) {
        if (timeUs < 0L || sampleRate <= 0) return
        if (sampleRate != this.sampleRate) {
            this.sampleRate = sampleRate
            alpha = (1.0 - exp(-2.0 * PI * CUTOFF_HZ / sampleRate)).toFloat()
        }
        // A seek or a new stream: the filter's memory belongs to other audio, and so does the open slot.
        val frameUs = 1_000_000L / sampleRate
        if (lastTimeUs == Long.MIN_VALUE || timeUs < lastTimeUs || timeUs - lastTimeUs > 2 * frameUs + 1) {
            closeSlot()
            low1 = 0f
            low2 = 0f
        }
        lastTimeUs = timeUs
        low1 += alpha * (sample - low1)
        low2 += alpha * (low1 - low2)
        val current = (timeUs * BASS_SLOTS_PER_SECOND / 1_000_000L).toInt()
        if (current != slot) {
            closeSlot()
            slot = current
        }
        sumSquares += low2.toDouble() * low2
        count++
    }

    /**
     * 0 (quiet) to 1 (as hard as the hardest hit within [WINDOW_SLOTS] either side) at [positionMs]; 0 where
     * nothing was measured. Judged against the hits around it rather than the whole track's, so a verse's
     * kicks pulse as plainly as a drop's; never against less than [QUIET_SHARE] of the track's loud hits, so
     * a fade-out or a quiet intro does not pump at full strength.
     */
    fun pulseAt(positionMs: Long): Float {
        val reference = reference
        if (reference <= 0f || positionMs < 0L) return 0f
        val index = (positionMs * BASS_SLOTS_PER_SECOND / 1000L).toInt()
        val level = levels.getOrNull(index) ?: return 0f
        var local = reference * QUIET_SHARE
        for (slot in maxOf(0, index - WINDOW_SLOTS)..minOf(levels.lastIndex, index + WINDOW_SLOTS)) {
            if (levels[slot] > local) local = levels[slot]
        }
        return ((level / local - FLOOR) / (1f - FLOOR)).coerceIn(0f, 1f)
    }

    /** The measured levels, for storing; [fromLevels] rebuilds an envelope that pulses the same. */
    fun levels(): FloatArray {
        closeSlot()
        reference = percentile()
        return levels.copyOf()
    }

    private fun closeSlot() {
        if (slot < 0 || count == 0) return
        store(slot, sqrt(sumSquares / count).toFloat())
        slot = -1
        sumSquares = 0.0
        count = 0
    }

    private fun store(
        index: Int,
        level: Float,
    ) {
        if (index !in 0 until MAX_SLOTS || !level.isFinite()) return
        if (index >= levels.size) levels = levels.copyOf(maxOf(index + 1, levels.size * 2, 64).coerceAtMost(MAX_SLOTS))
        levels[index] = level
        if (level <= 0f) return
        histogram[bin(level)]++
        histogramCount++
        // A percentile over the histogram costs its bin count, so refresh it about once a second of audio.
        if (histogramCount % BASS_SLOTS_PER_SECOND == 1) reference = percentile()
    }

    /** The level [REFERENCE_PERCENTILE] of measured slots stay under: the track's loud hits. */
    private fun percentile(): Float {
        val target = (histogramCount * REFERENCE_PERCENTILE).toInt()
        var seen = 0
        for (index in histogram.indices) {
            seen += histogram[index]
            if (seen > target) return levelOf(index + 1)
        }
        return levelOf(HISTOGRAM_BINS)
    }

    companion object {
        /** Kick drums and bass lines sit below this; vocals and hi-hats do not. */
        const val CUTOFF_HZ = 150.0

        // Six hours, the same ceiling the waveform keeps.
        private const val MAX_SLOTS = 6 * 3600 * BASS_SLOTS_PER_SECOND

        /** Slots below this share of the hardest nearby hit do not pulse at all. */
        private const val FLOOR = 0.5f
        private const val REFERENCE_PERCENTILE = 0.95f

        /** Two seconds either side: about a bar at most tempos, short enough that a drop does not mute the verse before it. */
        private const val WINDOW_SLOTS = 2 * BASS_SLOTS_PER_SECOND

        /** The quietest stretch still compared with this share of the track's loud hits. */
        private const val QUIET_SHARE = 0.15f

        // Levels from 1e-5 to 1 (full scale) on a log scale.
        private const val HISTOGRAM_BINS = 200
        private const val MIN_DECADE = -5f

        private fun bin(level: Float): Int = ((log10(level) - MIN_DECADE) / -MIN_DECADE * HISTOGRAM_BINS).toInt().coerceIn(0, HISTOGRAM_BINS - 1)

        private fun levelOf(binEdge: Int): Float = 10f.pow(MIN_DECADE + binEdge.toFloat() / HISTOGRAM_BINS * -MIN_DECADE)

        /** An envelope over [stored], levels an earlier [levels] call returned (scaled any way). */
        fun fromLevels(stored: FloatArray): BassEnvelope =
            BassEnvelope().apply {
                stored.forEachIndexed { index, level -> store(index, level) }
                reference = percentile()
            }
    }
}
