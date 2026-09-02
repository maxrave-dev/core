package com.maxrave.media3.audio

import com.maxrave.domain.data.player.AudioEffects
import com.maxrave.domain.data.player.DelayEffect
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Parity of the delay/echo against the filter desktop actually runs.
 *
 * The reference vectors under `src/test/resources/audio` were produced by ffmpeg 9.0.1:
 *
 * ```
 * ffmpeg -f lavfi -i "anoisesrc=d=1:c=pink:r=48000:a=0.5:s=7" -ac 2 -c:a pcm_s16le echo_in.wav
 * ffmpeg -i echo_in.wav -f s16le echo_in.raw
 * ffmpeg -i echo_in.wav -f s16le \
 *   -af "aecho=0.699999988:1:400|800|1200|1600:0.135000005|0.0607499965|0.0273374971|0.0123018743" \
 *   echo_ref.raw
 * head -c 384000 echo_ref.raw > echo_ref_400_045_030.raw
 * ```
 *
 * Three things about that invocation are deliberate.
 *
 * The gains and decays are the numbers `DelayEffect(400, 0.45f, 0.3f).taps()` produces, printed to
 * nine significant digits so ffmpeg's `%f` parse lands on the identical `float`. Given those, the
 * kernel is bit-exact: all 192 000 samples match, because it rounds each tap product to `float` the
 * way `aecho`'s `int16_t x float` multiply does instead of accumulating the lot in `double`. The
 * plan's acceptance bar was ±1 LSB, but the assertion below demands exactness, because widening
 * that product back to `double` is a plausible tidy-up — it is the more accurate arithmetic — and
 * it moves ten samples. That has to fail loudly instead of sitting inside a tolerance. Desktop
 * arrives at the same Floats by another route: its `aecho` argument is formatted with
 * `Float.toString()`, which round trips. What is asserted here is that the arithmetic matches
 * `aecho`, not that a decimal string survives one.
 *
 * The reference is cut to two seconds because `af_aecho.c` keeps emitting after end of input —
 * `request_frame` pushes `max_samples` silent frames through the same routine, so ffmpeg's output
 * is 1600 ms longer than its input. The processor has no equivalent: Media3 ends the stream when
 * the decoder does, and the tail is lost. The test therefore feeds one second of signal followed by
 * one second of silence, which is exactly what ffmpeg's flush does, and compares the whole two
 * seconds. That also matters for coverage: with a one-second window the 1200 ms and 1600 ms taps
 * would never contribute a single sample.
 *
 * The subject is [EchoKernel] rather than [EchoAudioProcessor], because this module's `src/test`
 * runs on a plain JVM against android.jar stubs with `isReturnDefaultValues` left unset, so
 * building an `AudioProcessor.AudioFormat` here would be a bet on which framework calls Media3
 * makes internally. The processor is a thin wrapper over exactly the code below.
 */
class EchoAudioProcessorTest {
    private val delay = DelayEffect(TIME_MS, FEEDBACK, MIX)

    @Test
    fun `taps match the values the reference vector was generated from`() {
        val taps = delay.taps()

        assertArrayEquals(intArrayOf(400, 800, 1200, 1600), taps.delaysMs)
        assertEquals(0.7, taps.inGain.toDouble(), TAP_TOLERANCE)
        assertEquals(1.0, taps.outGain.toDouble(), TAP_TOLERANCE)
        assertEquals(4, taps.decays.size)

        val expectedDecays = doubleArrayOf(0.135000005, 0.0607499965, 0.0273374971, 0.0123018743)
        expectedDecays.forEachIndexed { index, expected ->
            assertEquals("decay[$index]", expected, taps.decays[index].toDouble(), TAP_TOLERANCE)
        }
    }

    @Test
    fun `echo output is bit-exact with ffmpeg aecho`() {
        val input = readSamples("/audio/echo_in.raw")
        val expected = readSamples("/audio/echo_ref_400_045_030.raw")
        assertEquals("reference must cover the input plus its flush", input.size * 2, expected.size)

        val actual = runKernel(input, silentFramesAfter = input.size / CHANNELS, chunkFrames = CHUNK_FRAMES)

        assertEquals(expected.size, actual.size)
        var differing = 0
        var worst = 0
        var worstAt = -1
        expected.indices.forEach { index ->
            val diff = abs(expected[index] - actual[index])
            if (diff != 0) differing++
            if (diff > worst) {
                worst = diff
                worstAt = index
            }
        }
        // The shape of a failure names its cause: a handful of samples out by exactly 1 LSB is the
        // tap product having been widened to double, while a wholesale mismatch is a structural
        // break — a tap count, a ring offset, the frame index.
        assertEquals(
            "differing samples out of ${expected.size}; worst $worst LSB at $worstAt " +
                "(expected ${expected.getOrNull(worstAt)}, got ${actual.getOrNull(worstAt)})",
            0,
            differing,
        )
    }

    @Test
    fun `chunk boundaries do not change the output`() {
        val input = readSamples("/audio/echo_in.raw")

        val irregular = runKernel(input, silentFramesAfter = 0, chunkFrames = CHUNK_FRAMES)
        val single = runKernel(input, silentFramesAfter = 0, chunkFrames = intArrayOf(input.size / CHANNELS))

        assertArrayEquals(single, irregular)
    }

    @Test
    fun `a null delay bypasses, and switching back starts from silence`() {
        val kernel = EchoKernel()
        kernel.configure(SAMPLE_RATE, CHANNELS)

        kernel.sync(AudioEffects.NONE)
        assertTrue("no delay configured means nothing to do", kernel.isBypassed)

        val enabled = AudioEffects(delay, null)
        kernel.sync(enabled)
        assertFalse(kernel.isBypassed)

        // Record a second of full-scale signal, then switch the effect off and straight back on.
        // The line has to come back empty: the audio in it belongs to a passage the listener has
        // already heard, and replaying it under whatever is playing now is not an echo of anything.
        val loud = ShortArray(SAMPLE_RATE * CHANNELS) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        pushFrames(kernel, loud, 0, loud.size)
        kernel.sync(AudioEffects.NONE)
        assertTrue(kernel.isBypassed)
        kernel.sync(AudioEffects(delay, null))
        assertFalse(kernel.isBypassed)

        val silence = ShortArray(SAMPLE_RATE * CHANNELS)
        val out = pushFrames(kernel, silence, 0, silence.size)
        assertArrayEquals("a cleared line cannot echo", IntArray(out.size), out)
    }

    private fun runKernel(
        input: IntArray,
        silentFramesAfter: Int,
        chunkFrames: IntArray,
    ): IntArray {
        val kernel = EchoKernel()
        kernel.configure(SAMPLE_RATE, CHANNELS)
        kernel.sync(AudioEffects(delay, null))

        val samples = IntArray(input.size + silentFramesAfter * CHANNELS)
        input.copyInto(samples)

        val out = IntArray(samples.size)
        var offset = 0
        var chunk = 0
        while (offset < samples.size) {
            val count = minOf(chunkFrames[chunk % chunkFrames.size] * CHANNELS, samples.size - offset)
            kernel.beginBuffer()
            repeat(count) { out[offset + it] = kernel.processInterleaved(samples[offset + it]) }
            offset += count
            chunk++
        }
        return out
    }

    private fun pushFrames(
        kernel: EchoKernel,
        samples: ShortArray,
        from: Int,
        count: Int,
    ): IntArray {
        kernel.beginBuffer()
        return IntArray(count) { kernel.processInterleaved(samples[from + it].toInt()) }
    }

    private fun readSamples(resource: String): IntArray {
        val bytes =
            checkNotNull(javaClass.getResourceAsStream(resource)) { "missing test resource $resource" }
                .use { it.readBytes() }
        // The vectors are s16le on disk whatever this machine's byte order is; only the live audio
        // buffers the processor sees are in native order.
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return IntArray(bytes.size / 2) { buffer.short.toInt() }
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        const val TIME_MS = 400
        const val FEEDBACK = 0.45f
        const val MIX = 0.3f

        /** Float comparison only has to catch a changed formula, not a changed last bit. */
        const val TAP_TOLERANCE = 1e-6

        /**
         * 1000, 4096, 4 and 2000 bytes, in frames. Media3 hands over whole frames but says nothing
         * about how many, and a four-byte buffer is the shortest one that carries a stereo frame —
         * which is the case that catches a channel cursor kept across buffers instead of restarted.
         */
        val CHUNK_FRAMES = intArrayOf(250, 1024, 1, 500)
    }
}
