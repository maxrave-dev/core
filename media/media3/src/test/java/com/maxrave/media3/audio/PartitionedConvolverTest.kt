package com.maxrave.media3.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Checks [PartitionedConvolver] against ffmpeg's `afir` — the filter desktop actually runs.
 *
 * The point of this test is parity, not plausibility. Android and desktop convolve the same
 * generated impulse through two entirely different implementations, and the only way to know a
 * reverb dialled in on a phone is the same room on a laptop is to hold one against the other on real
 * numbers. A test that merely asserted "the output is not silent" would have passed every wrong
 * version of this class written on the way to the right one.
 *
 * The three resources under `src/test/resources/audio` were produced once, with ffmpeg 9.0.1 and a
 * short Python script, and are checked in so the reference cannot drift:
 *
 * - `reverb_ir.wav` — 0.5 s stereo 48 kHz float32, decaying noise from a fixed-seed xorshift with a
 *   decaying one-pole low-pass, each channel normalised to unit energy and seeded differently. Same
 *   shape as `ReverbImpulseResponse`, deliberately not the same samples: this test is about the
 *   convolver, and pinning it to the generator would make one break look like the other.
 * - `reverb_in.wav` — `ffmpeg -f lavfi -i "anoisesrc=d=1:c=pink:r=48000:a=0.5:s=11" -ac 2
 *   -c:a pcm_s16le reverb_in.wav`
 * - `reverb_ref.raw` — `ffmpeg -i reverb_in.wav -i reverb_ir.wav -filter_complex
 *   "[0][1]afir=dry=1:wet=1:irnorm=-1" -f f32le -ac 2 reverb_ref.raw`. `irnorm=-1` turns off
 *   `afir`'s own normalisation, which the app also does — the impulse arrives already scaled and
 *   letting the filter scale it again would put the two platforms at different levels.
 *
 * Everything here stays on [PartitionedConvolver] and [Fft], which are plain Kotlin.
 * `ConvolutionReverbAudioProcessor` is not covered: constructing an `AudioProcessor.AudioFormat`
 * pulls in Android framework classes that are not stubbed in a local unit test, and the arithmetic
 * worth pinning down lives here anyway.
 */
class PartitionedConvolverTest {
    /**
     * The reverb must land within a ten-thousandth of full scale of `afir`.
     *
     * Well inside audibility — a 16-bit sample step is 3e-5 — and loose enough to absorb both
     * `afir`'s single-precision arithmetic and this convolver's narrowed spectrum storage. The
     * measured figure is 1e-7: the margin is deliberately wide so a future change to the storage
     * layout fails this test on being audibly wrong, not on drifting in the last bits.
     */
    private val tolerance = 1e-4f

    /**
     * Deliberately ragged, and none of them a multiple of the partition size.
     *
     * Media3 hands over whatever the decoder produced, so the accumulator has to survive writes that
     * straddle block boundaries, land exactly on one, and — the first entry — carry a single sample.
     */
    private val chunkSizes = intArrayOf(1, 4095, 1000, 4096, 3000, 96, 4000, 517)

    @Test
    fun `convolution matches the ffmpeg afir reference`() {
        val impulse = decodeWav(readResource(IMPULSE_RESOURCE))
        val input = decodeWav(readResource(INPUT_RESOURCE))
        val reference = decodeFloatRaw(readResource(REFERENCE_RESOURCE), CHANNELS)

        val frames = reference[0].size
        // Fed up to the next block boundary, because overlap-save only ever answers in whole
        // partitions: stopping at 48 000 would leave the last 2944 samples inside the convolver and
        // compare nothing at all over the last 60 ms of the second.
        val fed = ((frames + PartitionedConvolver.PARTITION_SIZE - 1) / PartitionedConvolver.PARTITION_SIZE) *
            PartitionedConvolver.PARTITION_SIZE

        var worst = 0f
        for (channel in 0 until CHANNELS) {
            val produced = convolve(impulse[channel], input[channel], fed)
            assertEquals("output count after the final block", fed, produced.size)
            for (index in 0 until frames) {
                val difference = abs(produced[index] - reference[channel][index])
                if (difference > worst) worst = difference
            }
        }
        assertTrue("max |diff| of $worst against afir exceeds $tolerance", worst < tolerance)
    }

    @Test
    fun `a delta impulse hands the input back unchanged`() {
        val input = decodeWav(readResource(INPUT_RESOURCE))[0]
        val fed = 2 * PartitionedConvolver.PARTITION_SIZE

        val produced = convolve(floatArrayOf(1f), input, fed)

        // Convolving with a single unit sample is the identity, so this pins down alignment on its
        // own: any block-sized delay, any off-by-one in which half of the transform is kept, and
        // every sample here would be wrong.
        assertEquals("output count after the final block", fed, produced.size)
        for (index in 0 until fed) {
            assertEquals("sample $index", input[index].toDouble(), produced[index].toDouble(), 1e-6)
        }
    }

    @Test
    fun `nothing comes out until a whole partition has arrived`() {
        val convolver = PartitionedConvolver(floatArrayOf(1f))
        val almost = FloatArray(PartitionedConvolver.PARTITION_SIZE - 1) { 1f }

        convolver.write(almost, 0, almost.size)
        assertEquals("one sample short of a block", 0, convolver.availableOutput)

        convolver.write(floatArrayOf(1f), 0, 1)
        assertEquals("the sample that completes the block", PartitionedConvolver.PARTITION_SIZE, convolver.availableOutput)
    }

    @Test
    fun `reset leaves no tail behind`() {
        val impulse = decodeWav(readResource(IMPULSE_RESOURCE))[0]
        val convolver = PartitionedConvolver(impulse)
        val scratch = FloatArray(PartitionedConvolver.PARTITION_SIZE)

        val loud = FloatArray(PartitionedConvolver.PARTITION_SIZE) { 1f }
        convolver.write(loud, 0, loud.size)
        convolver.read(scratch, 0, convolver.availableOutput)
        convolver.reset()
        assertEquals("reset drops finished output too", 0, convolver.availableOutput)

        // A half-second impulse still has five partitions' worth of that block to ring out. If reset
        // only cleared the accumulator and left the delay line loaded, this is where it would show:
        // silence in would come back as the tail of a sound the user has already skipped away from.
        val silence = FloatArray(PartitionedConvolver.PARTITION_SIZE)
        repeat(2) {
            convolver.write(silence, 0, silence.size)
            val available = convolver.availableOutput
            assertEquals("one block in, one block out", PartitionedConvolver.PARTITION_SIZE, available)
            convolver.read(scratch, 0, available)
            for (sample in scratch) {
                assertEquals("silence after reset", 0.0, sample.toDouble(), 1e-12)
            }
        }
    }

    @Test
    fun `the inverse transform undoes the forward one`() {
        val size = 1_024
        val fft = Fft(size)
        val real = DoubleArray(size) { sin(it * 0.031) + 0.25 * cos(it * 0.007) }
        val imaginary = DoubleArray(size)
        val original = real.copyOf()

        fft.forward(real, imaginary)
        fft.inverse(real, imaginary)

        // Catches the one mistake that is otherwise invisible in a convolver: an inverse that
        // forgets its 1/N would still produce a perfectly reverberant signal, just 1024 times too
        // loud, which reads as clipping rather than as a bug in the transform.
        for (index in 0 until size) {
            assertEquals("real $index", original[index], real[index], 1e-9)
            assertEquals("imaginary $index", 0.0, imaginary[index], 1e-9)
        }
    }

    /**
     * Feed [fed] samples of [input] in ragged chunks, draining after every write.
     *
     * Zero-padded when [input] is shorter than [fed], which is how the last partial block is pushed
     * out — the same trick `ConvolutionReverbAudioProcessor` uses at the end of a stream.
     */
    private fun convolve(
        impulse: FloatArray,
        input: FloatArray,
        fed: Int,
    ): FloatArray {
        val convolver = PartitionedConvolver(impulse)
        val source = FloatArray(fed)
        input.copyInto(source, destinationOffset = 0, startIndex = 0, endIndex = minOf(input.size, fed))

        val collected = ArrayList<Float>(fed)
        val scratch = FloatArray(fed)
        var position = 0
        var step = 0
        while (position < fed) {
            val count = minOf(chunkSizes[step % chunkSizes.size], fed - position)
            step++
            convolver.write(source, position, count)
            position += count
            val available = convolver.availableOutput
            if (available > 0) {
                convolver.read(scratch, 0, available)
                for (index in 0 until available) {
                    collected.add(scratch[index])
                }
            }
        }
        return collected.toFloatArray()
    }

    private fun readResource(name: String): ByteArray =
        checkNotNull(javaClass.getResourceAsStream(name)) { "missing test resource $name" }
            .use { it.readBytes() }

    /**
     * Decode a RIFF/WAVE file into one array per channel, scaled so full scale is 1.0.
     *
     * Chunks are walked rather than assumed to start at byte 44: ffmpeg writes a `LIST` chunk
     * between `fmt ` and `data` in the PCM file, so the fixed-offset shortcut every WAV snippet on
     * the internet uses would read 26 bytes of encoder metadata as audio.
     */
    private fun decodeWav(bytes: ByteArray): Array<FloatArray> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(bytes.size > HEADER_BYTES && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "not a RIFF file" }

        var encoding = 0
        var channelCount = 0
        var bitsPerSample = 0
        var dataStart = -1
        var dataLength = 0
        var position = HEADER_BYTES
        while (position + 8 <= bytes.size) {
            val id = String(bytes, position, 4, Charsets.US_ASCII)
            val size = buffer.getInt(position + 4)
            if (size < 0) break
            when (id) {
                "fmt " -> {
                    encoding = buffer.getShort(position + 8).toInt()
                    channelCount = buffer.getShort(position + 10).toInt()
                    bitsPerSample = buffer.getShort(position + 22).toInt()
                }
                "data" -> {
                    dataStart = position + 8
                    dataLength = size
                }
            }
            // Chunks are word-aligned: an odd size carries a pad byte that is not part of it.
            position += 8 + size + (size and 1)
        }
        check(dataStart >= 0 && channelCount > 0) { "no usable data chunk" }

        return when (encoding) {
            ENCODING_PCM -> {
                check(bitsPerSample == 16) { "expected 16-bit PCM, got $bitsPerSample" }
                val frames = dataLength / (2 * channelCount)
                Array(channelCount) { channel ->
                    FloatArray(frames) { frame ->
                        buffer.getShort(dataStart + (frame * channelCount + channel) * 2) / PCM16_FULL_SCALE
                    }
                }
            }
            ENCODING_FLOAT -> {
                check(bitsPerSample == 32) { "expected 32-bit float, got $bitsPerSample" }
                val frames = dataLength / (4 * channelCount)
                Array(channelCount) { channel ->
                    FloatArray(frames) { frame ->
                        buffer.getFloat(dataStart + (frame * channelCount + channel) * 4)
                    }
                }
            }
            else -> error("unsupported WAV encoding $encoding")
        }
    }

    /** Split headerless little-endian float32 — what `-f f32le` writes — into channels. */
    private fun decodeFloatRaw(
        bytes: ByteArray,
        channelCount: Int,
    ): Array<FloatArray> {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val frames = bytes.size / (4 * channelCount)
        return Array(channelCount) { channel ->
            FloatArray(frames) { frame ->
                buffer.getFloat((frame * channelCount + channel) * 4)
            }
        }
    }

    private companion object {
        const val IMPULSE_RESOURCE = "/audio/reverb_ir.wav"
        const val INPUT_RESOURCE = "/audio/reverb_in.wav"
        const val REFERENCE_RESOURCE = "/audio/reverb_ref.raw"

        const val CHANNELS = 2

        /** Bytes before the first chunk: "RIFF", the size, and "WAVE". */
        const val HEADER_BYTES = 12

        const val ENCODING_PCM = 1
        const val ENCODING_FLOAT = 3

        /**
         * ffmpeg converts 16-bit PCM to float by dividing by 32768, not 32767, so the reference was
         * computed against that scale and this has to match it exactly.
         */
        const val PCM16_FULL_SCALE = 32_768f
    }
}
