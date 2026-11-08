package com.maxrave.media3.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A radix-2 complex FFT of one fixed size, transforming in place.
 *
 * Written out rather than pulled in as a dependency for the same reason the equalizer writes its own
 * biquads: this has to give the same answer as the ffmpeg filter desktop runs, and the only way to
 * be sure of that is to own every line of it. It is also the innermost loop of the reverb — one
 * forward and one inverse transform per 4096-frame block per channel — so it is built to be
 * allocation-free once constructed. Everything that can be precomputed is: the twiddle factors and
 * the bit-reversal permutation are tables owned by the instance, and [forward] and [inverse] touch
 * nothing but the two arrays handed to them.
 *
 * Iterative rather than recursive, again for the hot path: recursion would allocate a stack frame
 * and two sub-arrays per level, thirteen levels deep, on every block.
 *
 * The instance is stateless between calls but the tables are shared, so one [Fft] may back many
 * transforms — but only from one thread at a time, which is what the audio chain gives it.
 *
 * @param size number of complex points; must be a power of two.
 */
class Fft(
    val size: Int,
) {
    /**
     * `cos(2*pi*k/size)` and `sin(2*pi*k/size)` for the first half turn.
     *
     * One table serves every stage: a butterfly at half-width `h` wants `exp(-2*pi*i*j/(2h))`, which
     * is entry `j * size / (2h)` of this table. Storing it per stage instead would cost the same
     * memory again and buy nothing.
     */
    private val twiddleCos = DoubleArray(size / 2)
    private val twiddleSin = DoubleArray(size / 2)

    /** Where element `i` has to move for the decimation-in-time passes to read in order. */
    private val reversed = IntArray(size)

    init {
        require(size > 0 && size and (size - 1) == 0) {
            "FFT size must be a positive power of two, was $size"
        }
        for (k in 0 until size / 2) {
            val angle = 2.0 * PI * k / size
            twiddleCos[k] = cos(angle)
            twiddleSin[k] = sin(angle)
        }
        var bits = 0
        while (1 shl bits < size) {
            bits++
        }
        for (index in 0 until size) {
            // `Integer.reverse` flips all 32 bits, so the significant ones land at the top and have
            // to be shifted back down. Cheaper and far easier to read than the incremental
            // carry-propagating form, and it runs once per instance rather than per block.
            reversed[index] = Integer.reverse(index) ushr (32 - bits)
        }
    }

    /** In-place forward transform: `X[k] = sum over n of x[n] * exp(-2*pi*i*k*n/size)`. */
    fun forward(
        real: DoubleArray,
        imaginary: DoubleArray,
    ) {
        transform(real, imaginary, FORWARD_SIGN)
    }

    /**
     * In-place inverse transform, scaled by `1/size`.
     *
     * The scaling lives here rather than at the call site because an unscaled inverse is not an
     * inverse, and a convolver that forgot it would be off by a factor of 8192 — loud enough to be
     * unmistakable, which is the only mercy in that particular bug.
     */
    fun inverse(
        real: DoubleArray,
        imaginary: DoubleArray,
    ) {
        transform(real, imaginary, INVERSE_SIGN)
        val scale = 1.0 / size
        for (index in 0 until size) {
            real[index] *= scale
            imaginary[index] *= scale
        }
    }

    /**
     * Cooley–Tukey, decimation in time.
     *
     * [sign] selects the direction: the twiddle is `cos + i * sign * sin`, so `-1` gives the forward
     * transform's `exp(-i*theta)` and `+1` the inverse's `exp(+i*theta)`. Flipping a sign is the
     * whole difference between the two, which is why there is one loop here and not two.
     */
    private fun transform(
        real: DoubleArray,
        imaginary: DoubleArray,
        sign: Double,
    ) {
        require(real.size >= size && imaginary.size >= size) {
            "FFT buffers must hold at least $size points"
        }

        for (index in 0 until size) {
            val target = reversed[index]
            // Only swap upward, or every pair would be swapped twice and land back where it started.
            if (target > index) {
                val tempReal = real[index]
                real[index] = real[target]
                real[target] = tempReal
                val tempImaginary = imaginary[index]
                imaginary[index] = imaginary[target]
                imaginary[target] = tempImaginary
            }
        }

        var half = 1
        while (half < size) {
            val twiddleStep = size / (half * 2)
            var blockStart = 0
            while (blockStart < size) {
                var lower = blockStart
                var twiddle = 0
                while (lower < blockStart + half) {
                    val upper = lower + half
                    val cosine = twiddleCos[twiddle]
                    val sine = sign * twiddleSin[twiddle]
                    val productReal = real[upper] * cosine - imaginary[upper] * sine
                    val productImaginary = real[upper] * sine + imaginary[upper] * cosine
                    real[upper] = real[lower] - productReal
                    imaginary[upper] = imaginary[lower] - productImaginary
                    real[lower] += productReal
                    imaginary[lower] += productImaginary
                    lower++
                    twiddle += twiddleStep
                }
                blockStart += half * 2
            }
            half *= 2
        }
    }

    private companion object {
        const val FORWARD_SIGN = -1.0
        const val INVERSE_SIGN = 1.0
    }
}
