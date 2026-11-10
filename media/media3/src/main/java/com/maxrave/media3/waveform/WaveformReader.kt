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
import androidx.media3.exoplayer.audio.AudioSink
import com.maxrave.common.MERGING_DATA_TYPE
import com.maxrave.domain.data.player.LiveStreamRegistry
import com.maxrave.domain.mediaservice.automix.TrackStructure
import com.maxrave.domain.mediaservice.automix.TrackStructureAnalyzer
import com.maxrave.domain.mediaservice.player.WAVEFORM_BARS_PER_SECOND
import com.maxrave.domain.repository.StreamRepository
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * SoundCloud observes PCM already consumed by the real player's [WaveformAudioSink], or a finished
 * `.wf` cache. Opening the UI never starts a download, creates a decoder or waits for AutoMix.
 *
 * AutoMix alone retains the existing offline MediaExtractor/MediaCodec analysis below. Its complete
 * waveform and 8 kHz [TrackStructureAnalyzer] result keep their existing `.wf` and `.mix` formats.
 */
@OptIn(UnstableApi::class)
internal class WaveformReader(
    private val context: Context,
    private val dataSourceFactory: DataSource.Factory,
    private val streamRepository: StreamRepository,
) {
    private val dir by lazy { File(context.cacheDir, "waveforms").apply { mkdirs() } }

    // Only AutoMix starts a separate analysis decode; UI waveform reads never wait for this lock.
    private val decodeLock = Mutex()
    private val playbackWaveforms = PlaybackWaveforms()

    suspend fun read(
        mediaId: String,
        onProgress: (FloatArray) -> Unit,
    ): FloatArray? =
        withContext(Dispatchers.IO) {
            if (mediaId.isBlank() || LiveStreamRegistry.isLive(mediaId)) return@withContext null
            val name = mediaId.replace(UNSAFE_FILE_CHARS, "_")
            // UI never starts another download/decoder or waits for AutoMix's decode lock.
            storedBars(name) ?: playbackWaveforms.observe(mediaId, onProgress)
        }

    fun playbackSink(delegate: AudioSink, mediaId: String?): AudioSink =
        if (mediaId.isNullOrBlank()) {
            delegate
        } else {
            WaveformAudioSink(delegate, mediaId.removePrefix(MERGING_DATA_TYPE.VIDEO), playbackWaveforms)
        }

    /** AutoMix's measurements of the track; null when it has none (yet, or ever). */
    suspend fun structure(mediaId: String): TrackStructure? =
        withContext(Dispatchers.IO) {
            if (mediaId.isBlank() || LiveStreamRegistry.isLive(mediaId)) return@withContext null
            val name = mediaId.replace(UNSAFE_FILE_CHARS, "_")
            val stored = File(dir, "$name.mix")
            dropOutdated(stored)
            if (stored.isFile) return@withContext TrackStructure.decode(stored.readText())
            decodeLock.withLock {
                if (stored.isFile) TrackStructure.decode(stored.readText()) else measure(mediaId, name)?.structure
            }
        }

    /** A `.mix` written by an older analysis is deleted so the track is measured again; an empty one stays. */
    private fun dropOutdated(stored: File) {
        if (stored.isFile && stored.length() > 0 && TrackStructure.decode(stored.readText()) == null) stored.delete()
    }

    private fun storedBars(name: String): FloatArray? {
        val stored = File(dir, "$name.wf")
        if (!stored.isFile || stored.length() == 0L) return null
        val bytes = stored.readBytes()
        return FloatArray(bytes.size) { (bytes[it].toInt() and 0xFF) / 255f }
    }

    private class Measured(
        val bars: FloatArray,
        val structure: TrackStructure?,
    )

    /** Downloads and decodes the track once for AutoMix, storing its completed measurements. */
    private suspend fun measure(
        mediaId: String,
        name: String,
    ): Measured? {
        val audio = File(dir, "$name.part")
        return try {
            if (!download(mediaId, audio)) {
                // Usually the format is not stored yet; the caller asks again shortly.
                Logger.d(TAG, "No audio for $mediaId yet")
                return null
            }
            val decoded = decode(audio)
            if (decoded == null) {
                Logger.w(TAG, "Could not decode $mediaId into a waveform")
                return null
            }
            val bars = decoded.bars
            File(dir, "$name.wf").writeBytes(ByteArray(bars.size) { (bars[it] * 255f).toInt().coerceIn(0, 255).toByte() })
            // Kept apart from the waveform: a failure here must not cost the bars already measured.
            val structure = decoded.pcm?.let { runCatching { TrackStructureAnalyzer.analyze(it) }.getOrNull() }
            // Written even when empty, so a track found unusable is not decoded again for AutoMix.
            File(dir, "$name.mix").writeText(structure?.encode().orEmpty())
            Logger.d(
                TAG,
                "AutoMix measured $mediaId: " +
                    (structure?.let { "${it.bpm} BPM, intro ends ${it.introEndMs} ms, last chorus ends ${it.lastChorusEndMs} ms, music ends ${it.musicEndMs} ms" } ?: "nothing usable"),
            )
            Measured(bars, structure)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "No waveform for $mediaId: ${e.message}")
            null
        } finally {
            audio.delete()
        }
    }

    /** Copies the audio for AutoMix's offline analysis, reopening at resolver chunk boundaries. */
    private suspend fun download(
        mediaId: String,
        target: File,
    ): Boolean {
        val buffer = ByteArray(64 * 1024)
        var position = 0L
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

    private class Decoded(
        val bars: FloatArray,
        /** Mono at [TrackStructureAnalyzer.SAMPLE_RATE]; null for a track too long to be worth analysing. */
        val pcm: ShortArray?,
    )

    private suspend fun decode(file: File): Decoded? {
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
            val downsampler =
                if (durationUs <= MAX_ANALYSIS_US) {
                    Downsampler((durationUs * TrackStructureAnalyzer.SAMPLE_RATE / 1_000_000).toInt() + TrackStructureAnalyzer.SAMPLE_RATE)
                } else {
                    null
                }

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
                        accumulate(output.order(ByteOrder.nativeOrder()), floatPcm, channels, sampleRate, info.presentationTimeUs, sums, counts, downsampler)
                    }
                    decoder.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
            }
            val rms = FloatArray(barCount) { if (counts[it] > 0) sqrt(sums[it] / counts[it]).toFloat() else 0f }
            val loudest = rms.maxOrNull()?.takeIf { it > 0f } ?: return null
            return Decoded(FloatArray(barCount) { (rms[it] / loudest).coerceIn(MIN_LEVEL, 1f) }, downsampler?.finish())
        } finally {
            codec?.let {
                runCatching { it.stop() }
                it.release()
            }
            extractor.release()
        }
    }

    /**
     * Adds one decoded buffer's mean square, per channel-averaged frame, to the bar its time falls in,
     * and its channel-averaged samples to [downsampler].
     */
    private fun accumulate(
        buffer: ByteBuffer,
        floatPcm: Boolean,
        channels: Int,
        sampleRate: Int,
        startUs: Long,
        sums: DoubleArray,
        counts: LongArray,
        downsampler: Downsampler?,
    ) {
        val channelCount = channels.coerceAtLeast(1)
        val bytesPerSample = if (floatPcm) 4 else 2
        val frames = buffer.remaining() / bytesPerSample / channelCount
        val startSeconds = startUs / 1_000_000.0
        for (frame in 0 until frames) {
            var squares = 0.0
            var mono = 0.0
            repeat(channelCount) {
                val sample = if (floatPcm) buffer.float.toDouble() else buffer.short / 32768.0
                squares += sample * sample
                mono += sample
            }
            downsampler?.add(mono / channelCount, sampleRate)
            val bar = ((startSeconds + frame.toDouble() / sampleRate) * WAVEFORM_BARS_PER_SECOND).toInt()
            if (bar in sums.indices) {
                sums[bar] += squares / channelCount
                counts[bar]++
            }
        }
    }

    /**
     * Averages the decoded audio down to [TrackStructureAnalyzer.SAMPLE_RATE]: every input frame goes
     * to the output sample its position falls in. The box average is the anti-alias filter, enough
     * for what the analyzer reads (energy every 10 ms, pitches up to 1 kHz).
     */
    private class Downsampler(
        capacity: Int,
    ) {
        private val pcm = ShortArray(capacity)
        private var written = 0
        private var inputFrames = 0L
        private var current = 0L
        private var sum = 0.0
        private var count = 0

        fun add(
            sample: Double,
            sampleRate: Int,
        ) {
            val index = inputFrames++ * TrackStructureAnalyzer.SAMPLE_RATE / sampleRate
            if (index != current) {
                emit()
                current = index
            }
            sum += sample
            count++
        }

        fun finish(): ShortArray {
            emit()
            return pcm.copyOf(written)
        }

        private fun emit() {
            if (count > 0 && current < pcm.size) {
                pcm[current.toInt()] = (sum / count * 32767).toInt().coerceIn(-32768, 32767).toShort()
                written = maxOf(written, current.toInt() + 1)
            }
            sum = 0.0
            count = 0
        }
    }

    private companion object {
        const val TAG = "Waveform"
        const val TIMEOUT_US = 10_000L

        // A long mix or a podcast episode is not worth holding on disk to draw one line.
        const val MAX_AUDIO_BYTES = 64L * 1024 * 1024

        // AutoMix analyses songs; past this a track is a mix or an episode, and its 8 kHz copy alone
        // would take 14 MB of memory.
        const val MAX_ANALYSIS_US = 15L * 60 * 1_000_000

        // Silence still draws a sliver, as SoundCloud's does, so the line never breaks.
        const val MIN_LEVEL = 0.04f
        val UNSAFE_FILE_CHARS = Regex("[^A-Za-z0-9_-]")
    }
}
