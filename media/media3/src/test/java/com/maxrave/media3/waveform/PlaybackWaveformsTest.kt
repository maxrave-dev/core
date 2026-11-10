package com.maxrave.media3.waveform

import androidx.media3.common.C
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PlaybackWaveformsTest {
    @Test
    fun firstBufferPublishesBeforeTrackEndsWithoutChangingAudio() {
        val store = PlaybackWaveforms()
        val pcm = pcm16(ShortArray(80) { 8192 })
        val original = pcm.array().copyOf()
        store.append("track", pcm, 0L, 8000, 1, C.ENCODING_PCM_16BIT)

        val bars = store.snapshot("track")!!
        assertEquals(1, bars.size)
        assertEquals(1f, bars[0], 0.0001f)
        assertEquals(0, pcm.position())
        assertEquals(original.size, pcm.limit())
        assertArrayEquals(original, pcm.array())
    }

    @Test
    fun usesBufferTimestampsAndKeepsUndecodedSeekGapsUnknown() {
        var clock = 0L
        val store = PlaybackWaveforms { clock }
        val pcm = pcm16(ShortArray(80) { 8192 })
        store.append("track", pcm, 1_600_000L, 8000, 1, C.ENCODING_PCM_16BIT)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f), store.snapshot("track"), 0.0001f)

        clock += 300_000_000L
        store.append("track", pcm, 0L, 8000, 1, C.ENCODING_PCM_16BIT)
        assertArrayEquals(floatArrayOf(1f, 0f, 1f), store.snapshot("track"), 0.0001f)
    }

    @Test
    fun measuresRelativeRmsAtTheExistingPointEightSecondGrid() {
        val store = PlaybackWaveforms()
        val pcm = pcm16(ShortArray(12_800) { if (it < 6400) 8192 else 16384 })
        store.append("track", pcm, 0L, 8000, 1, C.ENCODING_PCM_16BIT)
        assertArrayEquals(floatArrayOf(0.5f, 1f), store.snapshot("track"), 0.0001f)
    }

    @Test
    fun oppositePhaseStereoDoesNotCancelAndSilenceIsKnown() {
        val store = PlaybackWaveforms()
        val stereo = pcm16(ShortArray(160) { if (it % 2 == 0) 8192 else -8192 })
        store.append("stereo", stereo, 0L, 8000, 2, C.ENCODING_PCM_16BIT)
        assertEquals(1f, store.snapshot("stereo")!![0], 0.0001f)

        val silence = ByteBuffer.allocate(320).order(ByteOrder.LITTLE_ENDIAN)
        store.append("silence", silence, 0L, 8000, 1, C.ENCODING_PCM_FLOAT)
        assertEquals(0.04f, store.snapshot("silence")!![0], 0.0001f)
    }

    @Test
    fun trackIdentitiesStaySeparateAndOldUnobservedTracksAreEvicted() {
        val store = PlaybackWaveforms()
        repeat(9) { index ->
            store.append("track$index", pcm16(shortArrayOf(8192)), 0L, 8000, 1, C.ENCODING_PCM_16BIT)
        }
        assertNull(store.snapshot("track0"))
        assertNotNull(store.snapshot("track8"))
        assertNull(store.snapshot("never decoded"))
    }

    private fun pcm16(samples: ShortArray): ByteBuffer =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            samples.forEach { putShort(it) }
            flip()
        }
}
