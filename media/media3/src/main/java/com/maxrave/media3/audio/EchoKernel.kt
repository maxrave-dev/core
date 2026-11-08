package com.maxrave.media3.audio

import com.maxrave.domain.data.player.AudioEffects
import com.maxrave.domain.data.player.DelayEffect

private const val ECHO_PCM16_MIN = -32_768.0
private const val ECHO_PCM16_MAX = 32_767.0

/**
 * The delay/echo arithmetic, split out of [EchoAudioProcessor] so it can be tested.
 *
 * Nothing here touches Media3 or the Android framework, which is the entire reason it is its own
 * class: this module's `src/test` runs on a plain JVM against android.jar stubs and
 * `isReturnDefaultValues` is not set, so a test that had to build an
 * `AudioProcessor.AudioFormat` would be betting on which framework calls Media3 makes on the way.
 * The parity of this code against ffmpeg is the thing worth asserting, and it is asserted directly.
 *
 * The semantics are `af_aecho.c`'s, sample for sample, because desktop runs that exact filter
 * through mpv's `af` chain and one stored setting has to mean the same thing on both platforms:
 *
 * ```
 * out = in * in_gain
 * for each tap j:  out += line[index - samples[j]] * decay[j]
 * out *= out_gain
 * emit clip(out, INT16_MIN, INT16_MAX) truncated toward zero
 * line[index] = in          // the INPUT, not the output: no feedback path exists
 * index = (index + 1) mod ring
 * ```
 *
 * Three details of that listing are load-bearing and easy to get wrong. The line stores the input, so
 * a tap never echoes an echo — the decaying repeats come from the tap *list* the setting expands
 * into, not from feeding output back in. And the ring index is shared by every channel and advances
 * once per frame, not once per sample; ffmpeg gets that by restarting each channel's loop from the
 * same `delay_index`, which is what [processInterleaved] reproduces with its channel cursor.
 *
 * The third is the arithmetic width, which is the whole difference between "close" and identical.
 * In the C, `dbuf` is `int16_t*` and `decay` is `float*`, so every tap product is computed in
 * **float** and only the accumulator is `double`; `in_gain` and `out_gain` are `const double`
 * copies of float options, so those two multiplies are double. Summing the taps in double instead
 * is more accurate and therefore *wrong*: measured against the checked-in reference it moved 10
 * samples in 192 000 by one LSB, because a slightly better sum lands on the other side of a
 * truncation boundary. Rounding each product to float the way `aecho` does brings that to zero.
 */
class EchoKernel {
    private var sampleRate = 0
    private var channelCount = 0

    /** The effects the taps below were derived from; compared by identity, never by value. */
    private var appliedEffects: AudioEffects? = null

    /**
     * One ring per channel holding raw input samples, as `int16_t` exactly like `aecho`'s own
     * `s16p` delay buffer — so the arithmetic below reads back the same integers ffmpeg would.
     *
     * Empty until a delay is actually configured; see [ensureLines].
     */
    private var lines: Array<ShortArray> = emptyArray()

    /**
     * Length every ring will have once allocated, in samples per channel. Decided by the sample
     * rate alone, which is why it is known from [configure] even though nothing is allocated there.
     */
    private var ringLength = 0

    /** Where the next input frame is written. Shared by all channels, advanced once per frame. */
    private var writeIndex = 0

    /** Which channel of the current frame [processInterleaved] is about to be handed. */
    private var channelCursor = 0

    /** Each tap's delay converted to samples at [sampleRate]. */
    private var tapOffsets = IntArray(0)

    /** Left as `Float`: the tap products have to round exactly where `aecho`'s do. */
    private var tapDecays = FloatArray(0)

    /** `Double`, matching the `const double` copies `aecho` takes of its own float options. */
    private var inGain = 1.0
    private var outGain = 1.0

    /** True while no delay is configured, which is the whole of playback for most users. */
    var isBypassed = true
        private set

    /**
     * Work out how long the rings will have to be, and drop any that were sized for another format.
     *
     * Nothing is allocated here — see [ensureLines] for why the rings wait for a delay to actually
     * be switched on.
     */
    fun configure(
        sampleRate: Int,
        channelCount: Int,
    ) {
        this.sampleRate = sampleRate
        this.channelCount = channelCount
        ringLength = ((DelayEffect.MAX_TIME_MS.toLong() * DelayEffect.MAX_TAPS * sampleRate) / 1_000L).toInt()
        if (lines.size != channelCount || lines.firstOrNull()?.size != ringLength) {
            lines = emptyArray()
            writeIndex = 0
            channelCursor = 0
        } else {
            // Same geometry, different stream. ExoPlayer reconfigures the sink for every track, so
            // rebuilding the rings here would hand the collector megabytes per track for no reason;
            // they only have to be emptied, because what is in them belongs to the track that just
            // finished.
            flush()
        }
        // Forces a rebuild against the new sample rate on the first buffer.
        appliedEffects = null
    }

    /**
     * Allocate the rings if they are not already the right shape, and say whether they are usable.
     *
     * Called only once a delay is switched on, because the memory is not small: the longest
     * reachable delay is `MAX_TIME_MS * MAX_TAPS` = 24 s, which at 48 kHz stereo is 4.6 MB — per
     * ExoPlayer, and there can be three of them alive at once (current, crossfade secondary,
     * precached). Charging every listener that for an effect that ships off would be the wrong
     * trade, so the cost lands on the one user in the settings screen instead. [reset] gives it
     * back when the player is released.
     *
     * The size is still the fixed maximum rather than what the current setting needs, so dragging
     * Time or Feedback afterwards re-points the taps inside a line that is already full: a
     * reallocation would pull the recorded audio out from under a live echo and click.
     */
    private fun ensureLines(): Boolean {
        if (lines.size == channelCount && lines.firstOrNull()?.size == ringLength) return true
        if (channelCount <= 0 || ringLength <= 0) return false
        // The one allocation, on the audio thread, at the moment the switch is flipped. A single
        // buffer's worth of jitter there is the price of not holding the memory all the time.
        lines = Array(channelCount) { ShortArray(ringLength) }
        writeIndex = 0
        channelCursor = 0
        return true
    }

    /** Drop the recorded audio but keep the rings; the format has not changed. */
    fun flush() {
        lines.forEach { it.fill(0) }
        writeIndex = 0
        channelCursor = 0
    }

    /** Give the rings back. Called when the player they belong to is released. */
    fun reset() {
        lines = emptyArray()
        tapOffsets = IntArray(0)
        tapDecays = FloatArray(0)
        appliedEffects = null
        sampleRate = 0
        channelCount = 0
        ringLength = 0
        writeIndex = 0
        channelCursor = 0
        isBypassed = true
    }

    /** Re-derive the taps if — and only if — the supplied effects are a different object. */
    fun sync(effects: AudioEffects) {
        if (effects === appliedEffects) return
        appliedEffects = effects
        rebuild(effects.delay)
    }

    private fun rebuild(delay: DelayEffect?) {
        // `delay == null` is tested first on purpose: it is the state the app spends almost all of
        // its life in, and it must not reach [ensureLines] and allocate anything.
        if (delay == null || !ensureLines()) {
            // Entering bypass clears the rings rather than freezing them: what is in there was
            // recorded before the effect was switched off, and replaying it when the effect comes
            // back would splice a fragment of the old passage onto the front of the new one.
            if (!isBypassed) flush()
            isBypassed = true
            return
        }

        val taps = delay.taps()
        if (tapOffsets.size != taps.delaysMs.size) {
            tapOffsets = IntArray(taps.delaysMs.size)
            tapDecays = FloatArray(taps.delaysMs.size)
        }
        taps.delaysMs.forEachIndexed { index, delayMs ->
            // Truncated, not rounded: `af_aecho.c` assigns `delay[i] * sample_rate / 1000.0` to an
            // int. The clamp is a guard rather than maths — a tap of zero samples would read the
            // far end of the ring instead of the current frame, and one longer than the ring would
            // read some other tap's slot. Neither is reachable from DelayEffect's own range.
            tapOffsets[index] = (delayMs.toDouble() * sampleRate / 1_000.0).toInt().coerceIn(1, ringLength)
        }
        taps.decays.forEachIndexed { index, decay -> tapDecays[index] = decay }
        inGain = taps.inGain.toDouble()
        outGain = taps.outGain.toDouble()
        // Deliberately NOT clearing the rings: this is the path every Time/Feedback/Mix drag takes,
        // and pulling the recorded audio out from under a live echo is audible as a click.
        isBypassed = false
    }

    /**
     * Restart the channel cursor at the head of a buffer.
     *
     * Media3 guarantees a whole number of frames per buffer, so the first sample of every buffer
     * belongs to channel 0 — the same assumption [EqualizerAudioProcessor] makes when it restarts
     * its own counter.
     */
    fun beginBuffer() {
        channelCursor = 0
    }

    /**
     * One interleaved PCM 16-bit sample in, one out, both as the plain integer value.
     *
     * Channels must arrive in order: the ring only advances once the last channel of a frame has
     * been handed over, so that every channel of a frame reads and writes the same slot.
     */
    fun processInterleaved(input: Int): Int {
        val line = lines[channelCursor]
        var out = input * inGain
        var tap = 0
        while (tap < tapOffsets.size) {
            // ffmpeg's MOD is a single conditional subtraction rather than a remainder, and it is
            // enough because the sum is always below twice the ring length.
            var readIndex = writeIndex + ringLength - tapOffsets[tap]
            if (readIndex >= ringLength) readIndex -= ringLength
            // Float on purpose, and spelled out so it cannot be widened by a later tidy-up: this
            // is `dbuf[ix] * ctx->decay[j]`, a float multiply whose rounding is part of the answer.
            val product: Float = line[readIndex] * tapDecays[tap]
            out += product.toDouble()
            tap++
        }
        out *= outGain
        line[writeIndex] = input.toShort()

        channelCursor++
        if (channelCursor == channelCount) {
            channelCursor = 0
            writeIndex++
            if (writeIndex == ringLength) writeIndex = 0
        }
        // Clip then truncate toward zero, which is what `av_clipd` into an `int16_t` does.
        return out.coerceIn(ECHO_PCM16_MIN, ECHO_PCM16_MAX).toInt()
    }
}
