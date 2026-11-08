package com.maxrave.domain.mediaservice.automix

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.ln1p
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Measures a track's tempo, bars, sections and silent edges from its own audio, for AutoMix to
 * place a transition by. Input is the whole track as mono 16-bit PCM at [SAMPLE_RATE].
 *
 * - Energy every 10 ms, full band and below 200 Hz (two 2nd-order low-passes in a row).
 * - Onset envelope: rises in log energy, the low band weighted twice, minus its trailing 0.4 s mean.
 * - Tempo from a beat-grid comb rather than autocorrelation: on trap hi-hat rolls autocorrelation
 *   locks onto the roll (146.6 / 130.9 against a real 155 / 146); the comb finds the pulse. Folded
 *   into 100-200 BPM, the range DJ tools quote.
 * - Beats from Ellis' dynamic-programming tracker; bar phase from where the bass hits and where the
 *   loudness changes from bar to bar.
 * - Sections where the mean of four bars differs from the four before by more than 3 dB, and the
 *   intro and last chorus told apart from the rest by the loudness of the parts between them.
 */
object TrackStructureAnalyzer {
    const val SAMPLE_RATE = 8_000
    private const val HOP = SAMPLE_RATE / 100
    private const val FPS = SAMPLE_RATE.toDouble() / HOP

    private const val SILENCE_DB = -60.0
    private const val MIN_SOUND_SECONDS = 30
    private const val LOW_BAND_HZ = 200.0
    private const val MIN_BPM = 60.0
    private const val MAX_BPM = 200.0
    private const val TIGHTNESS = 100.0
    private const val SECTION_DB = 3.0
    private const val SECTION_BARS = 4
    private const val MUSIC_END_DB = 10.0
    private const val CHORUS_DB = 2.0
    private const val MAX_INTRO_MS = 45_000L
    private const val MAX_OUTRO_MS = 30_000L

    /** Null when the track has under 30 s of sound or too few beats to find a bar in. */
    fun analyze(pcm: ShortArray): TrackStructure? {
        val frames = pcm.size / HOP
        val full = DoubleArray(frames)
        val low = DoubleArray(frames)
        frameEnergies(pcm, full, low)
        val first = full.indexOfFirst { toDb(it) > SILENCE_DB }
        val last = full.indexOfLast { toDb(it) > SILENCE_DB }
        if (first < 0 || last - first < MIN_SOUND_SECONDS * FPS) return null

        val onset = onsetEnvelope(full, low)
        val (bpm, period) = combTempo(onset, first, last)
        val beats = beatTrack(onset, period).filter { it in first..last }.toIntArray()
        if (beats.size < 4 * SECTION_BARS * 2) return null
        val downbeats = downbeats(beats, full, low)
        // One more bar line, closing the last bar: beats stop with the sound, so the bar the song
        // ends on would otherwise have no end and no level.
        val barLines = downbeats + (downbeats.last() + (period * 4).roundToInt())
        val levels = barLevels(barLines, full)
        val changes = sections(barLines, levels)
        val endBar = musicEndBar(levels)
        val musicEndMs = toMs(barLines[endBar + 1])
        val (introEnd, lastChorusEnd) = partEdges(changes.map { it.first }, levels, endBar)
        return TrackStructure(
            bpm = (bpm * 100).roundToInt() / 100.0,
            beatMs = period / FPS * 1000,
            downbeatsMs = downbeats.map { toMs(it) },
            sections = changes.map { it.second },
            firstSoundMs = toMs(first),
            lastSoundMs = toMs(last),
            musicEndMs = musicEndMs,
            introEndMs = introEnd?.let { toMs(barLines[it]) }?.takeIf { it <= MAX_INTRO_MS },
            lastChorusEndMs = lastChorusEnd?.let { toMs(barLines[it]) }?.takeIf { musicEndMs - it <= MAX_OUTRO_MS },
        )
    }

    private fun toMs(frame: Int): Long = (frame * 1000 / FPS).toLong()

    private fun toDb(energy: Double): Double = 10 * log10(energy + 1e-12)

    /** Mean square per 10 ms frame, of the signal and of its band below [LOW_BAND_HZ]. */
    private fun frameEnergies(
        pcm: ShortArray,
        full: DoubleArray,
        low: DoubleArray,
    ) {
        // RBJ cookbook low-pass, Q = 1/sqrt(2), run twice for a 24 dB/octave slope.
        val w0 = 2 * PI * LOW_BAND_HZ / SAMPLE_RATE
        val alpha = sin(w0) / sqrt(2.0)
        val cosW = cos(w0)
        val a0 = 1 + alpha
        val b0 = (1 - cosW) / 2 / a0
        val b1 = (1 - cosW) / a0
        val a1 = -2 * cosW / a0
        val a2 = (1 - alpha) / a0
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        var z1 = 0.0
        var z2 = 0.0
        for (f in full.indices) {
            var sumFull = 0.0
            var sumLow = 0.0
            for (j in f * HOP until (f + 1) * HOP) {
                val x = pcm[j] / 32768.0
                val y = b0 * x + b1 * x1 + b0 * x2 - a1 * y1 - a2 * y2
                x2 = x1
                x1 = x
                // Second stage, fed by the first one's output.
                val z = b0 * y + b1 * y1 + b0 * y2 - a1 * z1 - a2 * z2
                y2 = y1
                y1 = y
                z2 = z1
                z1 = z
                sumFull += x * x
                sumLow += z * z
            }
            full[f] = sumFull / HOP
            low[f] = sumLow / HOP
        }
    }

    private fun onsetEnvelope(
        full: DoubleArray,
        low: DoubleArray,
    ): DoubleArray {
        val n = full.size
        val rises = DoubleArray(n)
        for (i in 1 until n) {
            rises[i] = max(0.0, ln1p(1000 * low[i]) - ln1p(1000 * low[i - 1])) +
                0.5 * max(0.0, ln1p(1000 * full[i]) - ln1p(1000 * full[i - 1]))
        }
        // Minus the trailing 0.4 s mean, so a loud stretch does not read as a stream of onsets.
        val window = (FPS * 0.4).toInt()
        val onset = DoubleArray(n)
        var sum = 0.0
        for (i in 0 until n) {
            sum += rises[i]
            if (i >= window) sum -= rises[i - window]
            onset[i] = max(0.0, rises[i] - sum / min(i + 1, window))
        }
        val rms = sqrt(onset.sumOf { it * it } / n).takeIf { it > 0.0 } ?: 1.0
        for (i in 0 until n) onset[i] /= rms
        return onset
    }

    /** The best mean onset strength on a beat grid of [bpm], over every phase of that grid. */
    private fun combScore(
        onset: DoubleArray,
        bpm: Double,
        start: Int,
        end: Int,
    ): Double {
        val period = 60 * FPS / bpm
        var best = 0.0
        for (phase in 0 until period.toInt()) {
            var sum = 0.0
            var count = 0
            var t = start + phase.toDouble()
            while (t < end - 2) {
                val i = t.roundToInt()
                sum += max(onset[i - 1], max(onset[i], onset[i + 1]))
                count++
                t += period
            }
            if (count > 0) best = max(best, sum / count)
        }
        return best
    }

    /** Tempo in BPM and the beat period in frames: a 0.5 BPM sweep, then a 0.05 BPM refinement. */
    private fun combTempo(
        onset: DoubleArray,
        first: Int,
        last: Int,
    ): Pair<Double, Double> {
        val start = max(first, 1)
        var top = MIN_BPM
        var topScore = -1.0
        for (step in 0..((MAX_BPM - MIN_BPM) * 2).toInt()) {
            val bpm = MIN_BPM + step * 0.5
            val score = combScore(onset, bpm, start, last)
            if (score > topScore) {
                topScore = score
                top = bpm
            }
        }
        var bpm = top
        var bestScore = -1.0
        for (step in -12..12) {
            val candidate = top + step * 0.05
            val score = combScore(onset, candidate, start, last)
            if (score > bestScore) {
                bestScore = score
                bpm = candidate
            }
        }
        while (bpm < 100) bpm *= 2
        return bpm to 60 * FPS / bpm
    }

    /** Ellis (2007): every frame's best path of beats about one [period] apart, traced back from the end. */
    private fun beatTrack(
        onset: DoubleArray,
        period: Double,
    ): IntArray {
        val n = onset.size
        val shortest = (period / 2).roundToInt()
        val longest = (period * 2).roundToInt()
        val penalty = DoubleArray(longest + 1) { gap -> if (gap == 0) 0.0 else TIGHTNESS * ln(gap / period).pow(2) }
        val score = DoubleArray(n)
        val back = IntArray(n) { -1 }
        for (t in 0 until n) {
            var best = 0.0
            var arg = -1
            for (p in max(0, t - longest)..(t - shortest)) {
                val value = score[p] - penalty[t - p]
                if (arg < 0 || value > best) {
                    best = value
                    arg = p
                }
            }
            score[t] = onset[t] + if (arg >= 0) best else 0.0
            back[t] = arg
        }
        var t = (max(0, n - period.toInt()) until n).maxByOrNull { score[it] } ?: return IntArray(0)
        val beats = ArrayList<Int>()
        while (t >= 0) {
            beats.add(t)
            t = back[t]
        }
        beats.reverse()
        return beats.toIntArray()
    }

    /**
     * The beat that starts each bar. Of the four phases, the one where the bass hits hardest, with
     * half a vote for where the loudness changes between bars: sections start on a bar's first beat,
     * which is what tells beat 1 from beat 3.
     */
    private fun downbeats(
        beats: IntArray,
        full: DoubleArray,
        low: DoubleArray,
    ): IntArray {
        val n = full.size
        val bassHits = DoubleArray(n)
        for (i in 1 until n) bassHits[i] = max(0.0, ln1p(1000 * low[i]) - ln1p(1000 * low[i - 1]))

        fun strength(beat: Int): Double {
            var strongest = 0.0
            for (i in max(0, beat - 3)..min(n - 1, beat + 3)) strongest = max(strongest, bassHits[i])
            return strongest
        }

        fun barDb(startBeat: Int): Double {
            val a = beats[startBeat]
            val b = beats[min(startBeat + 4, beats.size - 1)]
            if (b <= a) return -90.0
            var sum = 0.0
            for (i in a until b) sum += full[i]
            return toDb(sum / (b - a))
        }

        val hitScores =
            DoubleArray(4) { phase ->
                var sum = 0.0
                var k = phase
                while (k < beats.size) {
                    sum += strength(beats[k])
                    k += 4
                }
                sum
            }
        val jumpScores =
            DoubleArray(4) { phase ->
                var sum = 0.0
                var previous = Double.NaN
                var k = phase
                while (k < beats.size - 4) {
                    val level = barDb(k)
                    if (!previous.isNaN()) sum += abs(level - previous)
                    previous = level
                    k += 4
                }
                sum
            }
        val maxHits = hitScores.max().takeIf { it > 0 } ?: 1.0
        val maxJumps = jumpScores.max().takeIf { it > 0 } ?: 1.0
        val phase = (0 until 4).maxBy { hitScores[it] / maxHits + 0.5 * jumpScores[it] / maxJumps }
        return IntArray((beats.size - phase + 3) / 4) { beats[phase + it * 4] }
    }

    /** Loudness of each bar, in dB: one value per pair of neighbouring [barLines]. */
    private fun barLevels(
        barLines: IntArray,
        full: DoubleArray,
    ): DoubleArray =
        DoubleArray(barLines.size - 1) { i ->
            val a = barLines[i]
            val b = min(barLines[i + 1], full.size)
            if (b <= a) return@DoubleArray -90.0
            var sum = 0.0
            for (j in a until b) sum += full[j]
            toDb(sum / (b - a))
        }

    /**
     * The last bar played within 10 dB of the median bar. Nonstop ends its last 8-bar phrase on bar
     * 151 at -7 dB and is at -33 dB a bar later, its tail running two more seconds; counting phrases
     * from section changes instead landed two bars off, because its 2-bar breaks blur four-bar means.
     */
    private fun musicEndBar(levels: DoubleArray): Int {
        val median = levels.sorted()[levels.size / 2]
        return levels.indexOfLast { it >= median - MUSIC_END_DB }.coerceAtLeast(0)
    }

    /**
     * The intro's end and the last chorus's end, as bar indices, labelled by loudness alone. Parts are
     * the stretches between section changes up to the music's end; a chorus is a part within
     * [CHORUS_DB] of the loudest; the intro is a first part, not a chorus, at least [SECTION_DB]
     * quieter than both parts after it; the last chorus has an end only when a part follows it.
     * Null wherever loudness does not tell the parts apart: AutoMix then plays the next track from 0
     * and ends the outgoing one at its music end.
     */
    private fun partEdges(
        changeBars: List<Int>,
        levels: DoubleArray,
        endBar: Int,
    ): Pair<Int?, Int?> {
        val edges = (listOf(0) + changeBars.filter { it in 1..endBar } + (endBar + 1)).distinct()
        if (edges.size < 3) return null to null
        val partLevels = (0 until edges.size - 1).map { i -> (edges[i] until edges[i + 1]).map { levels[it] }.average() }
        val loudest = partLevels.max()
        val isChorus = partLevels.map { it >= loudest - CHORUS_DB }
        val introEnd = edges[1].takeIf { !isChorus[0] && partLevels[0] <= partLevels.drop(1).take(2).min() - SECTION_DB }
        val lastChorus = isChorus.lastIndexOf(true)
        val lastChorusEnd = edges[lastChorus + 1].takeIf { lastChorus < partLevels.size - 1 }
        return introEnd to lastChorusEnd
    }

    /** Section changes with the bar each starts on. */
    private fun sections(
        barLines: IntArray,
        levels: DoubleArray,
    ): List<Pair<Int, SectionChange>> {
        val found = ArrayList<Pair<Int, SectionChange>>()
        for (bar in SECTION_BARS..levels.size - SECTION_BARS) {
            var before = 0.0
            var after = 0.0
            for (j in bar - SECTION_BARS until bar) before += levels[j]
            for (j in bar until bar + SECTION_BARS) after += levels[j]
            val delta = (after - before) / SECTION_BARS
            if (abs(delta) <= SECTION_DB) continue
            val change = SectionChange(toMs(barLines[bar]), (delta * 10).roundToInt() / 10.0)
            val previous = found.lastOrNull()
            // One change per neighbourhood of four bars: the strongest.
            if (previous != null && bar - previous.first < SECTION_BARS) {
                if (abs(delta) > abs(previous.second.deltaDb)) found[found.lastIndex] = bar to change
            } else {
                found.add(bar to change)
            }
        }
        return found
    }
}
