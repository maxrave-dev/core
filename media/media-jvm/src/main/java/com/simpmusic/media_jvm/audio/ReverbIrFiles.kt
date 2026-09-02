package com.simpmusic.media_jvm.audio

import com.maxrave.domain.data.player.ReverbImpulseResponse
import com.maxrave.domain.data.player.ReverbPreset
import com.maxrave.logger.Logger
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val TAG = "ReverbIrFiles"

/** `WAVE_FORMAT_IEEE_FLOAT` — samples are 32-bit floats rather than scaled integers. */
private const val WAVE_FORMAT_IEEE_FLOAT: Short = 3

private const val CHANNELS = 2
private const val BITS_PER_SAMPLE = 32
private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8

/** `RIFF` + size + `WAVE`, a 16-byte `fmt ` chunk, and the `data` header. */
private const val HEADER_BYTES = 44

/**
 * Mirrors the `MAX_LENGTH_MS` cap inside `ReverbImpulseResponse.generate`, which is private there.
 *
 * Restating a constant is normally how two numbers drift apart; here it cannot go unnoticed for
 * long, because the only thing it feeds is [expectedFileBytes] — and if this were wrong, EVERY
 * cached file would look corrupt and be regenerated on every call, which is loud rather than
 * silent. [ReverbImpulseResponse.VERSION] covers the other direction: a change to the impulse maths
 * bumps it, and the version is part of the file NAME, so old files are bypassed rather than
 * measured.
 */
private const val MAX_IMPULSE_LENGTH_MS = 4_000

/**
 * The impulse response files the desktop reverb convolves against.
 *
 * Desktop cannot hand mpv an array of samples the way Android hands them to its convolver: the
 * graph reads the IR through `amovie`, which takes a path and opens it while mpv is still PARSING
 * the filter chain. So the shared generator's output has to exist as a file on disk before the
 * reverb entry is installed at all — that ordering, not the caching, is why this class exists.
 */
object ReverbIrFiles {
    /**
     * The impulse response for [preset], generating and caching it on first use.
     *
     * The generator version is part of the file name rather than something written inside the file:
     * changing the reverb maths then produces a different name, so an old cache is bypassed instead
     * of having to be detected and invalidated. For one [ReverbImpulseResponse.VERSION] the
     * generator is deterministic, so a cached file of the right SIZE is reused as-is rather than
     * rewritten — rewriting could only reproduce the same bytes while racing the mpv process
     * reading them.
     *
     * A file of the wrong size is regenerated, and that check is not academic: the atomic rename
     * below cannot protect a file left behind by an OLDER build, nor against the disk itself. A
     * short file goes wrong in two different ways, both measured against ffmpeg 9.0.1:
     *
     *  - Too short to open at all (a header-length stub, or empty) and `amovie` fails, which fails
     *    the whole `af` write — indistinguishable, from the caller's side, from a libmpv with no
     *    convolution filters. It latches that conclusion and disables reverb for the rest of the
     *    process, so one bad cache file would cost the feature entirely.
     *  - Truncated anywhere in the sample data and ffmpeg opens it happily, reporting whatever
     *    duration survived (a HALL cut in half read as 1.04 s instead of 2.22 s). Nothing fails;
     *    the room simply becomes a smaller room, permanently and silently.
     *
     * The second is the reason this checks a length rather than merely catching a failure.
     *
     * @return the file, or null if it could not be written — the caller must then install no
     *   reverb entry at all, since `amovie` pointed at a missing file fails the whole `af` chain
     *   and would take the equalizer down with it.
     */
    fun ensure(preset: ReverbPreset): File? {
        val folder = File(reverbFolderPath())
        val file = File(folder, "${preset.name.lowercase()}-v${ReverbImpulseResponse.VERSION}.wav")
        val expectedBytes = expectedFileBytes(preset)
        if (file.isFile) {
            if (file.length() == expectedBytes) return file
            Logger.w(
                TAG,
                "Cached ${preset.name} impulse response is ${file.length()} bytes, expected $expectedBytes; regenerating",
            )
            // Not checked: the rename below carries REPLACE_EXISTING, so a delete that fails —
            // Windows refuses one while another process holds the file open — costs nothing.
            file.delete()
        }

        var started: File? = null
        return try {
            folder.mkdirs()
            val impulse = ReverbImpulseResponse.generate(preset)
            // Written under a temporary name in the SAME directory and renamed into place, so a
            // reader can only ever see the finished file. mpv opens this path from its own thread
            // the instant the entry is installed, and a half-written WAV is not a recoverable error
            // there — it fails the chain exactly like a missing one. Same directory because a
            // rename is only atomic within one filesystem.
            val temp = File.createTempFile("${file.nameWithoutExtension}-", ".tmp", folder)
            started = temp
            temp.writeBytes(floatWav(impulse.left, impulse.right, impulse.sampleRate))
            moveIntoPlace(temp, file)
            file
        } catch (e: Exception) {
            Logger.e(TAG, "Could not write the ${preset.name} impulse response: ${e.message}", e)
            started?.delete()
            null
        }
    }
}

/**
 * Rename [temp] onto [target], atomically where the filesystem can.
 *
 * An atomic rename is what stops mpv from ever opening a half-written impulse response, so it is
 * the first choice — but `ATOMIC_MOVE` is a REQUEST, and a filesystem that cannot honour it refuses
 * the whole call rather than quietly degrading. Letting that exception through would cost the user
 * the entire reverb feature on an exotic mount, to protect them from a race that only opens if the
 * process dies inside this one call. The retry keeps everything else: same directory, same
 * replace-in-place, one filename appearing complete.
 */
private fun moveIntoPlace(
    temp: File,
    target: File,
) {
    try {
        Files.move(
            temp.toPath(),
            target.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    } catch (e: AtomicMoveNotSupportedException) {
        Logger.w(TAG, "Filesystem cannot rename atomically (${e.message}); falling back to a plain replace")
        Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun reverbFolderPath(): String =
    System.getProperty("user.home") + File.separator + ".simpmusic" + File.separator + "reverb"

/**
 * The exact length [floatWav] produces for [preset], and so the only length a healthy cache file
 * can have.
 *
 * Mirrors the frame count of `ReverbImpulseResponse.generate` — `min(preDelayMs + rt60Ms, cap)`
 * milliseconds at [ReverbImpulseResponse.SAMPLE_RATE], with the same integer division and the same
 * floor of one frame — because that function returns arrays it never resizes afterwards. Size is
 * the whole check on purpose: the generator is deterministic, so for a given VERSION the byte count
 * is fixed, and every way a cache file goes bad in practice (a process killed mid-write by an older
 * build, a truncating disk error, a partial copy) changes it. Content corruption that preserves the
 * length is not covered, and would need a digest the file has nowhere to carry.
 */
private fun expectedFileBytes(preset: ReverbPreset): Long {
    val lengthMs = minOf(preset.preDelayMs + preset.rt60Ms, MAX_IMPULSE_LENGTH_MS)
    val frames = maxOf(1, lengthMs * ReverbImpulseResponse.SAMPLE_RATE / 1_000)
    return HEADER_BYTES + frames.toLong() * CHANNELS * BYTES_PER_SAMPLE
}

/**
 * Encode [left] and [right] as an interleaved 32-bit float WAV.
 *
 * Float rather than 16-bit PCM because an impulse response normalised to unit ENERGY has a tiny
 * peak — quantising it to integers would throw away most of the tail, which is the part that
 * actually sounds like a room.
 *
 * The `fmt ` chunk stays at its 16-byte PCM length with no `fact` chunk following it. The IEEE
 * float spec asks for both extensions; FFmpeg's WAV demuxer does not need either, and every byte
 * here is read by exactly one program.
 */
private fun floatWav(
    left: FloatArray,
    right: FloatArray,
    sampleRate: Int,
): ByteArray {
    val frames = minOf(left.size, right.size)
    val dataBytes = frames * CHANNELS * BYTES_PER_SAMPLE
    val buffer =
        ByteBuffer
            .allocate(HEADER_BYTES + dataBytes)
            // RIFF is little-endian; ByteBuffer defaults to big.
            .order(ByteOrder.LITTLE_ENDIAN)

    buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
    buffer.putInt(HEADER_BYTES - 8 + dataBytes)
    buffer.put("WAVE".toByteArray(Charsets.US_ASCII))

    buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
    buffer.putInt(16)
    buffer.putShort(WAVE_FORMAT_IEEE_FLOAT)
    buffer.putShort(CHANNELS.toShort())
    buffer.putInt(sampleRate)
    buffer.putInt(sampleRate * CHANNELS * BYTES_PER_SAMPLE)
    buffer.putShort((CHANNELS * BYTES_PER_SAMPLE).toShort())
    buffer.putShort(BITS_PER_SAMPLE.toShort())

    buffer.put("data".toByteArray(Charsets.US_ASCII))
    buffer.putInt(dataBytes)
    for (i in 0 until frames) {
        buffer.putFloat(left[i])
        buffer.putFloat(right[i])
    }
    return buffer.array()
}
