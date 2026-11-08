package com.maxrave.media3.audio

/**
 * One channel of uniform partitioned convolution, streaming.
 *
 * This is the Android half of the reverb: the impulse response comes from
 * `com.maxrave.domain.data.player.ReverbImpulseResponse`, desktop hands the same samples to ffmpeg's
 * `afir`, and this class has to answer with the same numbers. It does, to within 1e-7 of full scale
 * — measured against `afir` on a checked-in reference (see `PartitionedConvolverTest`).
 *
 * **Why partitioned, and not the obvious thing.** A direct time-domain convolution against a 4 s
 * impulse is 192 000 multiply-accumulates *per output sample*: nine billion per second of stereo
 * audio, which no phone will do. A single FFT over the whole impulse is cheap but cannot stream —
 * it needs the entire input first. Uniform partitioning is the standard escape: the impulse is cut
 * into [PARTITION_SIZE]-sample pieces whose spectra are computed once at construction, the input is
 * transformed one block at a time, and the output is the sum of each input block's spectrum against
 * the piece of impulse it is now old enough to reach.
 *
 * **Latency, and why 4096.** Overlap-save cannot emit anything until a whole block has arrived, so
 * the first sample appears once [PARTITION_SIZE] frames are in — 85 ms at 48 kHz. That is
 * imperceptible for a reverb (the effect is a tail, not a transient) and it is paid once at the
 * start of a track, because the sink is already buffering far more than that. The alternative is a
 * smaller partition, and the cost is steep and non-linear: the lead measured `afir` burning twice
 * the CPU for partitions eight times smaller, since halving the block halves the work per FFT but
 * doubles how often every one of them runs. 4096 sits at the cheap end of that curve.
 *
 * **Alignment.** Output sample `n` is the convolution at input sample `n` — no delay of the block
 * size, no delay of the impulse length. That matters because the processor blends this against the
 * dry signal, and a half-block offset between the two would comb-filter the result rather than
 * reverberate it. It also matches `afir`, which the lead verified emits a delta impulse at sample 0
 * for every partition setting.
 *
 * **Memory.** The spectra dominate, and there are three of this object alive per channel at a time
 * (one per ExoPlayer), so the layout is chosen for size rather than for the shortest code. Two
 * economies, each worth a factor of two:
 *
 * - they are stored as `Float`, while every transform and every accumulation still runs in `Double`;
 * - only bins `0..PARTITION_SIZE` are kept. The input blocks and the impulse are both real signals,
 *   so each spectrum is Hermitian — `X[N-k]` is the conjugate of `X[k]` — and the upper half says
 *   nothing the lower half does not. It is rebuilt by [mirrorConjugateHalf] just before the inverse
 *   transform, which is the only place the whole spectrum is needed.
 *
 * Together that is a quarter of the obvious layout. CATHEDRAL, the longest preset at 4 s, costs
 * 3.1 MB per channel and so 6.2 MB in stereo, against 24.6 MB stored as full double spectra; HALL is
 * 3.5 MB stereo, PLATE 2.4 MB, ROOM 1.0 MB. On top of that each channel holds about 0.5 MB of
 * scratch and FFT tables whatever the preset, which is the price of keeping the arithmetic in
 * double — and it is the arithmetic, not the storage, that the parity tolerance rests on.
 *
 * Not thread-safe, and one instance holds one channel's history: the processor owns two.
 *
 * @param impulse the impulse response for this channel. Copied at construction, so the caller may
 *   reuse or discard the array afterwards.
 */
class PartitionedConvolver(
    impulse: FloatArray,
) {
    private val fft = Fft(FFT_SIZE)

    /** How many pieces the impulse was cut into; at least one even for an empty impulse. */
    private val partitions = maxOf(1, (impulse.size + PARTITION_SIZE - 1) / PARTITION_SIZE)

    /** Half-spectrum of impulse piece `p` at `p * SPECTRUM_BINS`; computed once, never touched again. */
    private val impulseReal = FloatArray(partitions * SPECTRUM_BINS)
    private val impulseImaginary = FloatArray(partitions * SPECTRUM_BINS)

    /** The frequency-delay line: the half-spectra of the last [partitions] input windows. */
    private val historyReal = FloatArray(partitions * SPECTRUM_BINS)
    private val historyImaginary = FloatArray(partitions * SPECTRUM_BINS)

    /** Which slot of the delay line the most recent input window went into. */
    private var historyIndex = 0

    /** The previous block of input, which forms the first half of the next transform window. */
    private val previousBlock = DoubleArray(PARTITION_SIZE)

    /** Input arriving since the last block completed, and how much of it there is. */
    private val incoming = DoubleArray(PARTITION_SIZE)
    private var incomingCount = 0

    /** Scratch for the transform of one input window, reused every block. */
    private val windowReal = DoubleArray(FFT_SIZE)
    private val windowImaginary = DoubleArray(FFT_SIZE)

    /** Scratch for the accumulated product across all partitions, reused every block. */
    private val productReal = DoubleArray(FFT_SIZE)
    private val productImaginary = DoubleArray(FFT_SIZE)

    /** Finished samples waiting to be read, between [pendingHead] and [pendingTail]. */
    private var pending = DoubleArray(FFT_SIZE)
    private var pendingHead = 0
    private var pendingTail = 0

    init {
        for (partition in 0 until partitions) {
            val start = partition * PARTITION_SIZE
            val length = minOf(PARTITION_SIZE, impulse.size - start)
            // Transformed through the block scratch rather than in place inside the spectra arrays:
            // the FFT reads its input from index 0 of whatever it is given, so a partition living at
            // an offset cannot be handed to it directly, and handing it a copy would transform the
            // copy and discard the answer.
            windowReal.fill(0.0)
            windowImaginary.fill(0.0)
            for (index in 0 until length) {
                windowReal[index] = impulse[start + index].toDouble()
            }
            // The upper half stays zero: that zero-padding to twice the partition size is what makes
            // the circular convolution the FFT computes agree with the linear one over the samples
            // this class keeps, which is the whole trick behind overlap-save.
            fft.forward(windowReal, windowImaginary)
            storeHalfSpectrum(impulseReal, impulseImaginary, partition)
        }
        windowReal.fill(0.0)
        windowImaginary.fill(0.0)
    }

    /** How many finished samples [read] can hand back right now. */
    val availableOutput: Int
        get() = pendingTail - pendingHead

    /**
     * Feed [count] samples from [input] starting at [offset].
     *
     * Any number of samples is accepted; they are accumulated until a whole block is present, which
     * is the only moment output appears. A caller that writes 1000 samples five times therefore sees
     * nothing, nothing, nothing, nothing, then 4096 — so callers must drive the [availableOutput]
     * count rather than assuming a write produces a matching read.
     */
    fun write(
        input: FloatArray,
        offset: Int,
        count: Int,
    ) {
        var index = 0
        while (index < count) {
            val chunk = minOf(count - index, PARTITION_SIZE - incomingCount)
            for (step in 0 until chunk) {
                incoming[incomingCount + step] = input[offset + index + step].toDouble()
            }
            incomingCount += chunk
            index += chunk
            if (incomingCount == PARTITION_SIZE) {
                convolveBlock()
            }
        }
    }

    /**
     * Move up to [count] finished samples into [output] at [offset], returning how many moved.
     *
     * Fewer than asked for is normal, not an error: the class only ever holds whole blocks of
     * finished audio and the caller is asking in whatever size the audio sink handed it.
     */
    fun read(
        output: FloatArray,
        offset: Int,
        count: Int,
    ): Int {
        val moved = minOf(count, availableOutput)
        for (index in 0 until moved) {
            output[offset + index] = pending[pendingHead + index].toFloat()
        }
        pendingHead += moved
        if (pendingHead == pendingTail) {
            pendingHead = 0
            pendingTail = 0
        }
        return moved
    }

    /**
     * Forget every sample ever seen, keeping the impulse.
     *
     * For a seek or a track change: the tail of the previous audio must not bleed across the cut,
     * and the partial block held in [incoming] belongs to samples that will never be asked for
     * again.
     */
    fun reset() {
        previousBlock.fill(0.0)
        incoming.fill(0.0)
        incomingCount = 0
        historyReal.fill(0f)
        historyImaginary.fill(0f)
        historyIndex = 0
        pendingHead = 0
        pendingTail = 0
    }

    /**
     * End of stream: drop what is left instead of draining it.
     *
     * A convolver genuinely has more to say here — the partial block, plus the impulse's whole
     * length of tail still working its way through the delay line. None of it is emitted, and that
     * is deliberate rather than an omission. Media3 flushes at a seek or a track change, where the
     * next audio starts immediately and a tail spliced in front of it would be heard as the previous
     * song leaking into this one. At a genuine end of playback there is no buffer left to put the
     * tail into anyway. Identical to [reset] today; it exists under its own name because the two are
     * different questions and only one of them is likely to change.
     */
    fun flush() {
        reset()
    }

    /**
     * Transform the newest window, multiply it against every partition, and keep the valid half.
     *
     * The window is `[previous block | incoming block]`, so it carries the [PARTITION_SIZE] samples
     * of history each output needs to reach back through. Circular wraparound corrupts the first
     * half of the result and leaves the second half equal to the true linear convolution — hence
     * "overlap-save", and hence only the upper half being appended.
     *
     * Partition `p` is paired with the window from `p` blocks ago, which is what turns a stack of
     * short convolutions back into one long one.
     */
    private fun convolveBlock() {
        previousBlock.copyInto(windowReal, destinationOffset = 0)
        incoming.copyInto(windowReal, destinationOffset = PARTITION_SIZE)
        windowImaginary.fill(0.0)
        fft.forward(windowReal, windowImaginary)

        historyIndex = if (historyIndex + 1 == partitions) 0 else historyIndex + 1
        storeHalfSpectrum(historyReal, historyImaginary, historyIndex)

        productReal.fill(0.0)
        productImaginary.fill(0.0)
        for (partition in 0 until partitions) {
            var slot = historyIndex - partition
            if (slot < 0) slot += partitions
            val window = slot * SPECTRUM_BINS
            val response = partition * SPECTRUM_BINS
            for (bin in 0 until SPECTRUM_BINS) {
                // Widened back to double for the arithmetic: the spectra are stored narrow to keep
                // three convolvers per channel inside a phone's heap, but a running sum over up to
                // 47 partitions is exactly where single precision would start to tell.
                val windowRe = historyReal[window + bin].toDouble()
                val windowIm = historyImaginary[window + bin].toDouble()
                val responseRe = impulseReal[response + bin].toDouble()
                val responseIm = impulseImaginary[response + bin].toDouble()
                productReal[bin] += windowRe * responseRe - windowIm * responseIm
                productImaginary[bin] += windowRe * responseIm + windowIm * responseRe
            }
        }
        mirrorConjugateHalf()
        fft.inverse(productReal, productImaginary)

        appendPending(productReal, PARTITION_SIZE, PARTITION_SIZE)
        incoming.copyInto(previousBlock)
        incomingCount = 0
    }

    /** Narrow the transform sitting in the window scratch into slot [index] of [real]/[imaginary]. */
    private fun storeHalfSpectrum(
        real: FloatArray,
        imaginary: FloatArray,
        index: Int,
    ) {
        val offset = index * SPECTRUM_BINS
        for (bin in 0 until SPECTRUM_BINS) {
            real[offset + bin] = windowReal[bin].toFloat()
            imaginary[offset + bin] = windowImaginary[bin].toFloat()
        }
    }

    /**
     * Rebuild the upper half of the product spectrum from the lower one.
     *
     * A product of Hermitian spectra is itself Hermitian, and so is a sum of them, so the accumulated
     * product is the spectrum of a real signal even though only half of it was computed. Reflecting
     * it — same real part, negated imaginary — restores exactly what a full-width accumulation would
     * have produced, which is what the inverse transform needs to hand back a real block.
     *
     * Bins 0 and [PARTITION_SIZE] are skipped because each is its own mirror: DC and Nyquist have no
     * partner to be conjugate with.
     */
    private fun mirrorConjugateHalf() {
        for (bin in 1 until PARTITION_SIZE) {
            productReal[FFT_SIZE - bin] = productReal[bin]
            productImaginary[FFT_SIZE - bin] = -productImaginary[bin]
        }
    }

    /**
     * Queue [count] finished samples from [source].
     *
     * The buffer compacts before it grows, because a caller that drains after every write — which
     * the audio processor does — leaves the head walking forward through a buffer that is mostly
     * free space. Growth only happens if someone writes far more than they read, and then only once.
     */
    private fun appendPending(
        source: DoubleArray,
        from: Int,
        count: Int,
    ) {
        if (pendingTail + count > pending.size) {
            val kept = availableOutput
            if (kept + count > pending.size) {
                var capacity = pending.size
                while (capacity < kept + count) {
                    capacity *= 2
                }
                val grown = DoubleArray(capacity)
                pending.copyInto(grown, destinationOffset = 0, startIndex = pendingHead, endIndex = pendingTail)
                pending = grown
            } else {
                pending.copyInto(pending, destinationOffset = 0, startIndex = pendingHead, endIndex = pendingTail)
            }
            pendingHead = 0
            pendingTail = kept
        }
        source.copyInto(pending, destinationOffset = pendingTail, startIndex = from, endIndex = from + count)
        pendingTail += count
    }

    companion object {
        /** Samples per partition; see the latency note in the class documentation. */
        const val PARTITION_SIZE: Int = 4096

        /** Twice the partition, so the zero-padded circular convolution has room to be linear. */
        const val FFT_SIZE: Int = 2 * PARTITION_SIZE

        /**
         * Bins actually stored per spectrum: DC, everything up to Nyquist, and Nyquist itself.
         *
         * The rest is the conjugate mirror of these, so keeping it would be storing the same numbers
         * twice — see the memory note in the class documentation.
         */
        private const val SPECTRUM_BINS: Int = FFT_SIZE / 2 + 1
    }
}
