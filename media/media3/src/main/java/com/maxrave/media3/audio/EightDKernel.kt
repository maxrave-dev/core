package com.maxrave.media3.audio

import com.maxrave.domain.data.player.EightDEffect
import com.maxrave.domain.data.player.EightDHead
import com.maxrave.domain.data.player.EightDHeadShadow
import com.maxrave.domain.data.player.EightDOrbit
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Per-stream PCM cursor. Settings are sampled per buffer, phase advances per audio frame.
 *
 * The song circles the head through [EightDHead]: each ear hears its own channel after its own
 * interaural delay and through its own head-shadow filter, so the song keeps its stereo. Desktop
 * runs the same head shadow but cannot delay (aeval keeps no sample history), so the delay is
 * Android-only.
 *
 * The phase is the kernel's own once it has joined the orbit: a flush keeps it. Media3 flushes the
 * pipeline both on a seek and on every speed/pitch change (DefaultAudioSink
 * applyAudioProcessorPlaybackParametersAndSkipSilence → setupAudioProcessors), and only the seek
 * empties the AudioTrack; rejoining the orbit there would throw the angle back by everything the
 * AudioTrack still holds.
 */
class EightDKernel(private val orbit: EightDOrbit) {
    private val leftShadow = EightDHeadShadow()
    private val rightShadow = EightDHeadShadow()

    // Each channel's history for the interaural delay, a power of two so the index wraps with a mask.
    private var leftHistory = DoubleArray(0)
    private var rightHistory = DoubleArray(0)
    private var mask = 0
    private var writeIndex = 0
    private var rate = 0

    @Volatile
    private var started = false
    private var joined = false
    private var renderedBeforeStart = false
    private var activeEffect: EightDEffect? = null
    private var targetWet = 0.0
    private var wet = 0.0
    private var wetStep = 1.0
    private var phaseStep = 0.0

    var phaseRadians = 0.0
        private set
    var left = 0.0
        private set
    var right = 0.0
        private set

    val isBypassed: Boolean get() = targetWet == 0.0 && wet == 0.0

    /**
     * The player has been told to play. ExoPlayer renders while paused (ExoPlayerImplInternal
     * doSomeWork), so a precached player fills its AudioTrack minutes before it is heard, at an
     * orbit angle nobody knows yet. That audio stays dry, and the first buffer after this joins
     * the orbit with the switch-on fade.
     */
    fun markStarted() {
        started = true
    }

    fun beginBuffer(sampleRate: Int) {
        val sr = sampleRate.coerceAtLeast(1)
        if (sr != rate) configure(sr)
        val snapshot = orbit.snapshot()
        val effect = snapshot.effect
        if (!joined) {
            if (!started) {
                renderedBeforeStart = true
                targetWet = 0.0
                wet = 0.0
                return
            }
            joined = true
            phaseRadians = snapshot.phaseRadians
            // Nothing rendered yet: start at full depth. Otherwise the dry audio is heard first.
            wet = if (effect != null && !renderedBeforeStart) 1.0 else 0.0
        } else if (isBypassed && effect != null) {
            // Switched on mid-stream: join the live orbit, then fade in.
            phaseRadians = snapshot.phaseRadians
        }
        // Retuning an active processor never changes its current phase.
        effect?.let { activeEffect = it }
        targetWet = if (effect != null) 1.0 else 0.0
        phaseStep = (activeEffect?.radiansPerSecond ?: 0.0) / sr
        wetStep = 1_000.0 / (sr * EightDEffect.TRANSITION_MS)
    }

    fun process(
        inputLeft: Double,
        inputRight: Double,
    ) {
        if (isBypassed) {
            left = inputLeft
            right = inputRight
            return
        }
        leftHistory[writeIndex] = inputLeft
        rightHistory[writeIndex] = inputRight
        val thetaLeft = EightDHead.incidence(phaseRadians, EightDHead.Ear.LEFT)
        val thetaRight = EightDHead.incidence(phaseRadians, EightDHead.Ear.RIGHT)
        val pole = EightDHead.shadowPole(thetaLeft)
        val earLeft =
            leftShadow.process(
                delayed(leftHistory, EightDHead.earDelaySeconds(thetaLeft) * rate),
                EightDHead.shadowGain(thetaLeft),
                pole,
            )
        val earRight =
            rightShadow.process(
                delayed(rightHistory, EightDHead.earDelaySeconds(thetaRight) * rate),
                EightDHead.shadowGain(thetaRight),
                pole,
            )
        writeIndex = (writeIndex + 1) and mask
        left = inputLeft * (1 - wet) + earLeft * wet
        right = inputRight * (1 - wet) + earRight * wet
        phaseRadians = EightDOrbit.wrapPhase(phaseRadians + phaseStep)
        wet = if (targetWet > wet) (wet + wetStep).coerceAtMost(targetWet) else (wet - wetStep).coerceAtLeast(targetWet)
    }

    /** The sink was reset: the next audio starts a new stream. Whether the player plays is unchanged. */
    fun reset() {
        joined = false
        renderedBeforeStart = false
        activeEffect = null
        targetWet = 0.0
        wet = 0.0
        clearHistory()
    }

    /**
     * The sample of [history] [samples] behind the one just written, by 4-point Catmull-Rom
     * interpolation. Linear interpolation would low-pass whatever sits half a sample off (−6 dB at
     * 16 kHz at 48 kHz) by an amount that changes as the source turns; the price is an overshoot
     * of at most 1.25× on full-scale edges between samples. Both ears read one sample later than
     * asked, so the newer neighbour always exists; equal on both sides, it shifts nothing.
     */
    private fun delayed(
        history: DoubleArray,
        samples: Double,
    ): Double {
        val position = samples + 1
        val whole = floor(position).toInt()
        val t = position - whole
        val p0 = history[(writeIndex - whole + 1) and mask]
        val p1 = history[(writeIndex - whole) and mask]
        val p2 = history[(writeIndex - whole - 1) and mask]
        val p3 = history[(writeIndex - whole - 2) and mask]
        val c1 = 0.5 * (p2 - p0)
        val c2 = p0 - 2.5 * p1 + 2 * p2 - 0.5 * p3
        val c3 = 0.5 * (p3 - p0) + 1.5 * (p1 - p2)
        return ((c3 * t + c2) * t + c1) * t + p1
    }

    private fun configure(sampleRate: Int) {
        rate = sampleRate
        var size = 1
        // The longest read reaches delay + 1 (offset) + 2 (the older neighbours) behind.
        val needed = ceil(EightDHead.MAX_DELAY_SECONDS * sampleRate).toInt() + 4
        while (size < needed) size = size shl 1
        leftHistory = DoubleArray(size)
        rightHistory = DoubleArray(size)
        mask = size - 1
        leftShadow.setSampleRate(sampleRate)
        rightShadow.setSampleRate(sampleRate)
        clearHistory()
    }

    private fun clearHistory() {
        leftHistory.fill(0.0)
        rightHistory.fill(0.0)
        writeIndex = 0
        leftShadow.reset()
        rightShadow.reset()
    }
}
