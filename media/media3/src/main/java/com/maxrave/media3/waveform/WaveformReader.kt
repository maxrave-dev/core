package com.maxrave.media3.waveform

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.maxrave.domain.data.player.LiveStreamRegistry
import com.maxrave.domain.mediaservice.player.WAVEFORM_BARS_PER_SECOND
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Builds the SoundCloud style's waveform for a track from the audio the player itself streams.
 *
 * The bytes are read through [dataSourceFactory], the player's own resolving, cache-backed chain:
 * what the player already cached is not downloaded again, and what this reads first lands in the
 * player cache for the player. They are decoded with MediaExtractor + MediaCodec and reduced to one
 * RMS value per 1 / [WAVEFORM_BARS_PER_SECOND] seconds. A finished waveform is a few hundred bytes
 * kept under the cache directory, so a track is decoded once.
 */
@OptIn(UnstableApi::class)
internal class WaveformReader(
    private val context: Context,
    private val dataSourceFactory: DataSource.Factory,
    private val streamRepository: StreamRepository,
) {
    private val dir by lazy { File(context.cacheDir, "waveforms").apply { mkdirs() } }

    suspend fun read(mediaId: String): FloatArray? =
        withContext(Dispatchers.IO) {
            if (mediaId.isBlank() || LiveStreamRegistry.isLive(mediaId)) return@withContext null
            val name = mediaId.replace(UNSAFE_FILE_CHARS, "_")
            val stored = File(dir, "$name.wf")
            if (stored.isFile && stored.length() > 0) {
                val bytes = stored.readBytes()
                return@withContext FloatArray(bytes.size) { (bytes[it].toInt() and 0xFF) / 255f }
            }
            val audio = File(dir, "$name.part")
            try {
                if (!download(mediaId, audio)) {
                    // Usually the format is not stored yet; the caller asks again shortly.
                    Logger.d(TAG, "No audio for $mediaId yet")
                    return@withContext null
                }
                val bars = decode(audio)
                if (bars == null) {
                    Logger.w(TAG, "Could not decode $mediaId into a waveform")
                    return@withContext null
                }
                stored.writeBytes(ByteArray(bars.size) { (bars[it] * 255f).toInt().coerceIn(0, 255).toByte() })
                bars
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.w(TAG, "No waveform for $mediaId: ${e.message}")
                null
            } finally {
                audio.delete()
            }
        }

    /**
     * Copies the whole audio stream to [target]. The resolver serves at most one 5 MiB chunk per
     * open, so this reopens at each chunk boundary until the format's own length is reached; it
     * never opens at the end of the file, which is where HTTP answers 416.
     */
    private suspend fun download(
        mediaId: String,
        target: File,
    ): Boolean {
        val buffer = ByteArray(64 * 1024)
        var position = 0L
        // Known only once the first chunk is open: opening is what makes the resolver replace a stale
        // or refused format, so the length read after it belongs to the stream actually being read.
        var length = Long.MAX_VALUE
        target.outputStream().buffered().use { out ->
            while (position < length) {
                currentCoroutineContext().ensureActive()
                val source = dataSourceFactory.createDataSource()
                var readThisOpen = 0L
                try {
                    source.open(
                        DataSpec
                            .Builder()
                            .setUri(mediaId)
                            .setKey(mediaId)
                            .setPosition(position)
                            .build(),
                    )
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = source.read(buffer, 0, buffer.size)
                        if (read == C.RESULT_END_OF_INPUT) break
                        out.write(buffer, 0, read)
                        readThisOpen += read
                    }
                } finally {
                    source.close()
                }
                // A source that gives nothing back would otherwise reopen at the same spot forever.
                if (readThisOpen == 0L) return false
                position += readThisOpen
                if (length == Long.MAX_VALUE) {
                    length =
                        streamRepository
                            .getNewFormat(mediaId)
                            .firstOrNull()
                            ?.contentLength
                            ?.takeIf { it in 1..MAX_AUDIO_BYTES }
                            ?: return false
                }
            }
        }
        return true
    }

    private suspend fun decode(file: File): FloatArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val track =
                (0 until extractor.trackCount).firstOrNull {
                    extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: return null
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: return null
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            if (durationUs <= 0L) return null
            val barCount = (durationUs / 1_000_000.0 * WAVEFORM_BARS_PER_SECOND).toInt().coerceAtLeast(1)
            val sums = DoubleArray(barCount)
            val counts = LongArray(barCount)

            val decoder = MediaCodec.createDecoderByType(mime)
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            var sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var floatPcm = false
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val input = decoder.getInputBuffer(inIndex)
                        val size = if (input != null) extractor.readSampleData(input, 0) else -1
                        if (size < 0) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outFormat = decoder.outputFormat
                    sampleRate = outFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = outFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    floatPcm =
                        outFormat.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        outFormat.getInteger(MediaFormat.KEY_PCM_ENCODING) == AudioFormat.ENCODING_PCM_FLOAT
                } else if (outIndex >= 0) {
                    val output = decoder.getOutputBuffer(outIndex)
                    if (output != null && info.size > 0) {
                        output.position(info.offset)
                        output.limit(info.offset + info.size)
                        accumulate(output.order(ByteOrder.nativeOrder()), floatPcm, channels, sampleRate, info.presentationTimeUs, sums, counts)
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            val rms = FloatArray(barCount) { if (counts[it] > 0) sqrt(sums[it] / counts[it]).toFloat() else 0f }
            val loudest = rms.maxOrNull()?.takeIf { it > 0f } ?: return null
            return FloatArray(barCount) { (rms[it] / loudest).coerceIn(MIN_LEVEL, 1f) }
        } finally {
            codec?.let {
                runCatching { it.stop() }
                it.release()
            }
            extractor.release()
        }
    }

    /** Adds one decoded buffer's mean square, per channel-averaged frame, to the bar its time falls in. */
    private fun accumulate(
        buffer: ByteBuffer,
        floatPcm: Boolean,
        channels: Int,
        sampleRate: Int,
        startUs: Long,
        sums: DoubleArray,
        counts: LongArray,
    ) {
        val channelCount = channels.coerceAtLeast(1)
        val bytesPerSample = if (floatPcm) 4 else 2
        val frames = buffer.remaining() / bytesPerSample / channelCount
        val startSeconds = startUs / 1_000_000.0
        for (frame in 0 until frames) {
            var squares = 0.0
            repeat(channelCount) {
                val sample = if (floatPcm) buffer.float.toDouble() else buffer.short / 32768.0
                squares += sample * sample
            }
            val bar = ((startSeconds + frame.toDouble() / sampleRate) * WAVEFORM_BARS_PER_SECOND).toInt()
            if (bar in sums.indices) {
                sums[bar] += squares / channelCount
                counts[bar]++
            }
        }
    }

    private companion object {
        const val TAG = "Waveform"
        const val TIMEOUT_US = 10_000L

        // A long mix or a podcast episode is not worth holding on disk to draw one line.
        const val MAX_AUDIO_BYTES = 64L * 1024 * 1024

        // Silence still draws a sliver, as SoundCloud's does, so the line never breaks.
        const val MIN_LEVEL = 0.04f
        val UNSAFE_FILE_CHARS = Regex("[^A-Za-z0-9_-]")
    }
}
