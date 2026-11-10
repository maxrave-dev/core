package com.maxrave.media3.waveform

import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.maxrave.domain.data.player.LiveStreamRegistry
import com.maxrave.logger.Logger
import java.nio.ByteBuffer

/** Reads only PCM actually consumed by the existing sink; never owns playback or another decoder. */
@OptIn(UnstableApi::class)
internal class WaveformAudioSink(
    delegate: AudioSink,
    private val mediaId: String,
    private val waveforms: PlaybackWaveforms,
) : ForwardingAudioSink(delegate) {
    private var format: Format? = null
    private var streamOffsetUs = 0L
    private var currentBuffer: ByteBuffer? = null
    private var bufferStart = 0
    private var recordedPosition = 0
    private var captureFailed = false

    // A waveform needs decoded samples. Encoded passthrough/offload bypasses PCM entirely.
    override fun supportsFormat(format: Format): Boolean = format.sampleMimeType == MimeTypes.AUDIO_RAW && super.supportsFormat(format)

    override fun getFormatSupport(format: Format): Int =
        if (format.sampleMimeType == MimeTypes.AUDIO_RAW) super.getFormatSupport(format) else AudioSink.SINK_FORMAT_UNSUPPORTED

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport = AudioOffloadSupport.DEFAULT_UNSUPPORTED

    override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
        super.configure(audioSinkConfig)
        format = audioSinkConfig.format.takeIf { it.sampleMimeType == MimeTypes.AUDIO_RAW }
        currentBuffer = null
    }

    override fun setOutputStreamOffsetUs(outputStreamOffsetUs: Long) {
        streamOffsetUs = outputStreamOffsetUs
        super.setOutputStreamOffsetUs(outputStreamOffsetUs)
    }

    override fun handleBuffer(
        buffer: ByteBuffer,
        presentationTimeUs: Long,
        encodedAccessUnitCount: Int,
    ): Boolean {
        if (buffer !== currentBuffer) {
            currentBuffer = buffer
            bufferStart = buffer.position()
            recordedPosition = bufferStart
        }
        val input = buffer.asReadOnlyBuffer()
        // Playback exceptions and the delegate's return value are deliberately unchanged.
        val handled = super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        val format = format
        if (!captureFailed && format != null && !LiveStreamRegistry.isLive(mediaId)) {
            try {
                val sampleBytes = PlaybackWaveforms.bytesPerSample(format.pcmEncoding)
                val frameBytes = sampleBytes * format.channelCount
                if (sampleBytes > 0 && frameBytes > 0 && format.sampleRate > 0) {
                    val consumed = buffer.position().coerceAtMost(input.limit())
                    val frameEnd = bufferStart + (consumed - bufferStart) / frameBytes * frameBytes
                    if (frameEnd > recordedPosition) {
                        input.position(recordedPosition)
                        input.limit(frameEnd)
                        val framesBefore = (recordedPosition - bufferStart) / frameBytes
                        val mediaTimeUs = presentationTimeUs - streamOffsetUs + framesBefore * 1_000_000L / format.sampleRate
                        waveforms.append(mediaId, input, mediaTimeUs, format.sampleRate, format.channelCount, format.pcmEncoding)
                        recordedPosition = frameEnd
                    }
                }
            } catch (e: Exception) {
                // A preview must never stop audio; no URL, cookie or exception message is logged.
                captureFailed = true
                Logger.w("Waveform", "PCM preview disabled: ${e::class.simpleName}")
            }
        }
        if (handled) currentBuffer = null
        return handled
    }

    override fun flush() {
        currentBuffer = null
        super.flush()
    }

    override fun reset() {
        currentBuffer = null
        format = null
        super.reset()
    }
}
