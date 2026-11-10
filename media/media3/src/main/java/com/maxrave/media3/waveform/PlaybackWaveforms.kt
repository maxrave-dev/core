package com.maxrave.media3.waveform

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import com.maxrave.domain.mediaservice.player.WAVEFORM_BARS_PER_SECOND
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterNotNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.LinkedHashMap
import kotlin.math.sqrt

/** Small in-memory previews measured from the PCM the real player has already decoded. */
@OptIn(UnstableApi::class)
internal class PlaybackWaveforms(
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private class Track {
        val updates = MutableStateFlow<FloatArray?>(null)
        var observers = 0
        var sums = DoubleArray(64)
        var counts = LongArray(64)
        var lastBar = -1
        var publishedBar = -1
        var publishedAt = Long.MIN_VALUE
    }

    private val tracks = LinkedHashMap<String, Track>(8, 0.75f, true)

    private fun track(mediaId: String): Track {
        val result = tracks.getOrPut(mediaId) { Track() }
        val iterator = tracks.entries.iterator()
        while (tracks.size > MAX_TRACKS && iterator.hasNext()) {
            val candidate = iterator.next()
            if (candidate.key != mediaId && candidate.value.observers == 0) iterator.remove()
        }
        return result
    }

    suspend fun observe(
        mediaId: String,
        onProgress: (FloatArray) -> Unit,
    ): FloatArray? {
        val track = synchronized(this) { track(mediaId).also { it.observers++ } }
        try {
            // This collector lives for the displayed track, not for a separate file decode. Its
            // latest snapshot remains available after EOS/pause and is never cleared at EOF.
            track.updates.filterNotNull().collect { bars ->
                currentCoroutineContext().ensureActive()
                onProgress(bars)
            }
        } finally {
            synchronized(this) { track.observers-- }
        }
        return null
    }

    @Synchronized
    fun snapshot(mediaId: String): FloatArray? = tracks[mediaId]?.updates?.value

    /** Does not change the caller's PCM bytes, position or limit. Zero slots mean not decoded yet. */
    @Synchronized
    fun append(
        mediaId: String,
        pcm: ByteBuffer,
        startUs: Long,
        sampleRate: Int,
        channels: Int,
        encoding: Int,
    ) {
        val sampleBytes = bytesPerSample(encoding)
        if (mediaId.isBlank() || startUs < 0L || sampleRate <= 0 || channels <= 0 || sampleBytes == 0) return
        val frameBytes = sampleBytes * channels
        val frames = pcm.remaining() / frameBytes
        if (frames == 0) return
        val firstBar = (startUs / 1_000_000.0 * WAVEFORM_BARS_PER_SECOND).toInt()
        if (firstBar !in 0 until MAX_BARS) return
        val lastUs = startUs + (frames - 1L) * 1_000_000L / sampleRate
        val lastBar = (lastUs / 1_000_000.0 * WAVEFORM_BARS_PER_SECOND).toInt().coerceAtMost(MAX_BARS - 1)
        val track = track(mediaId)
        if (lastBar >= track.sums.size) {
            val size = maxOf(lastBar + 1, track.sums.size * 2).coerceAtMost(MAX_BARS)
            track.sums = track.sums.copyOf(size)
            track.counts = track.counts.copyOf(size)
        }
        val data = pcm.asReadOnlyBuffer().order(if (encoding == C.ENCODING_PCM_16BIT_BIG_ENDIAN) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN)
        for (frame in 0 until frames) {
            val timeUs = startUs + frame * 1_000_000L / sampleRate
            val bar = (timeUs / 1_000_000.0 * WAVEFORM_BARS_PER_SECOND).toInt()
            if (bar >= MAX_BARS) break
            var squares = 0.0
            repeat(channels) {
                val sample =
                    when (encoding) {
                        C.ENCODING_PCM_8BIT -> ((data.get().toInt() and 0xFF) - 128) / 128.0
                        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> data.short / 32768.0
                        C.ENCODING_PCM_24BIT -> {
                            val value = (data.get().toInt() and 0xFF) or ((data.get().toInt() and 0xFF) shl 8) or (data.get().toInt() shl 16)
                            value / 8388608.0
                        }
                        C.ENCODING_PCM_32BIT -> data.int / 2147483648.0
                        else -> data.float.toDouble().takeIf { it.isFinite() } ?: 0.0
                    }
                squares += sample * sample
            }
            track.sums[bar] += squares / channels
            track.counts[bar]++
            track.lastBar = maxOf(track.lastBar, bar)
        }
        val now = nanoTime()
        if (track.lastBar > track.publishedBar || track.publishedAt == Long.MIN_VALUE || now - track.publishedAt >= PUBLISH_INTERVAL_NS) {
            val rms = FloatArray(track.lastBar + 1) { if (track.counts[it] > 0L) sqrt(track.sums[it] / track.counts[it]).toFloat() else 0f }
            val peak = rms.maxOrNull()?.takeIf { it > 0f } ?: 1f
            track.updates.value = FloatArray(rms.size) { if (track.counts[it] == 0L) 0f else (rms[it] / peak).coerceIn(0.04f, 1f) }
            track.publishedBar = track.lastBar
            track.publishedAt = now
        }
    }

    companion object {
        private const val MAX_TRACKS = 8
        // At most six hours per retained track; ordinary songs use only a few KB.
        private const val MAX_BARS = 27_000
        private const val PUBLISH_INTERVAL_NS = 250_000_000L

        fun bytesPerSample(encoding: Int): Int =
            when (encoding) {
                C.ENCODING_PCM_8BIT -> 1
                C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 2
                C.ENCODING_PCM_24BIT -> 3
                C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
                else -> 0
            }
    }
}
