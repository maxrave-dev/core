package com.simpmusic.media_jvm.mpv

import com.maxrave.domain.mediaservice.automix.TrackStructure
import com.maxrave.domain.mediaservice.automix.TrackStructureAnalyzer
import com.maxrave.domain.mediaservice.player.WAVEFORM_BARS_PER_SECOND
import com.maxrave.domain.mediaservice.player.WaveformProgress
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Builds the SoundCloud style's waveform on Desktop, matching Android's WaveformReader: one level per
 * 1 / [WAVEFORM_BARS_PER_SECOND] s, each 0..1 against the loudest bar, kept under ~/.simpmusic/waveforms.
 *
 * mpv shares no cache between handles, so the track's audio is played once more through a second,
 * silent handle. A lavfi chain resamples it to 8 kHz mono 16-bit, cuts it into one frame per bar,
 * measures each frame's RMS with `astats`, and `ametadata` prints the values to a file that is read
 * incrementally while decoding. The audio that comes out of the chain goes to `ao=pcm`, which writes
 * it raw to a second file as fast as it decodes (a 4-minute track in under a second): that copy is
 * what AutoMix's [TrackStructureAnalyzer] reads, and its result is kept beside the waveform as
 * `<id>.mix`. Every bundled slice carries ao_pcm.
 *
 * The chain needs `asetnsamples`, `astats` and `ametadata`. The macOS and Windows slices carry every
 * FFmpeg filter; the Linux slice carries only its whitelist (scripts/mpv-linux/Dockerfile). On a slice
 * without them the graph fails, nothing is written, and the caller gets null: a flat line.
 */
internal object MpvWaveformReader {
    private const val TAG = "Waveform"
    private const val SAMPLE_RATE = TrackStructureAnalyzer.SAMPLE_RATE
    private val SAMPLES_PER_BAR = (SAMPLE_RATE / WAVEFORM_BARS_PER_SECOND).roundToInt()

    // A stream that has not ended in this long is not worth holding a decoder for.
    private const val TIMEOUT_MS = 120_000L

    // Silence still draws a sliver, as on Android, so the line never breaks.
    private const val MIN_LEVEL = 0.04f

    // AutoMix analyses songs; past this a track is a mix or an episode (see WaveformReader).
    private const val MAX_ANALYSIS_BYTES = 15L * 60 * SAMPLE_RATE * 2

    // One decode at a time: the waveform and AutoMix ask for the same tracks, into the same files.
    private val decodeLock = Mutex()
    private val progress = WaveformProgress()
    private val UNSAFE_FILE_CHARS = Regex("[^A-Za-z0-9_-]")

    private val dir: File by lazy {
        File(System.getProperty("user.home"), ".simpmusic" + File.separator + "waveforms").apply { mkdirs() }
    }

    /**
     * The stored waveform for [mediaId], or one measured from [sourceUrl]'s audio. [sourceUrl] is only
     * asked when nothing is stored, so a track heard before costs no network at all.
     */
    suspend fun read(
        mediaId: String,
        onProgress: (FloatArray) -> Unit,
        sourceUrl: suspend () -> String?,
    ): FloatArray? =
        withContext(Dispatchers.IO) {
            val name = mediaId.replace(UNSAFE_FILE_CHARS, "_")
            progress.observe(mediaId, onProgress) {
                storedBars(name) ?: decodeLock.withLock { storedBars(name) ?: measure(mediaId, name, sourceUrl)?.bars }
            }
        }

    /** AutoMix's measurements of the track, from the same decode as the waveform; null when it has none. */
    suspend fun structure(
        mediaId: String,
        sourceUrl: suspend () -> String?,
    ): TrackStructure? =
        withContext(Dispatchers.IO) {
            val name = mediaId.replace(UNSAFE_FILE_CHARS, "_")
            val stored = File(dir, "$name.mix")
            dropOutdated(stored)
            if (stored.isFile) return@withContext TrackStructure.decode(stored.readText())
            decodeLock.withLock {
                if (stored.isFile) TrackStructure.decode(stored.readText()) else measure(mediaId, name, sourceUrl)?.structure
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

    private suspend fun measure(
        mediaId: String,
        name: String,
        sourceUrl: suspend () -> String?,
    ): Measured? {
        val url = sourceUrl()
        if (url == null) {
            Logger.d(TAG, "No playable audio URL for $mediaId yet")
            return null
        }
        val levels = File(dir, "$name.levels")
        val pcm = File(dir, "$name.pcm")
        try {
            levels.delete()
            pcm.delete()
            if (!decode(url, levels, pcm) { progress.publish(mediaId, it) }) return null
            val bars = parse(levels)
            if (bars == null) {
                Logger.w(TAG, "Measured $mediaId but read no levels back")
                return null
            }
            progress.publish(mediaId, bars)
            File(dir, "$name.wf").writeBytes(ByteArray(bars.size) { (bars[it] * 255f).toInt().coerceIn(0, 255).toByte() })
            val structure =
                pcm
                    .takeIf { it.isFile && it.length() in 2..MAX_ANALYSIS_BYTES }
                    // Kept apart from the waveform: a failure here must not cost the bars already measured.
                    ?.let { runCatching { TrackStructureAnalyzer.analyze(readPcm(it)) }.getOrNull() }
            // Written even when empty, so a track found unusable is not decoded again for AutoMix.
            File(dir, "$name.mix").writeText(structure?.encode().orEmpty())
            Logger.d(
                TAG,
                "AutoMix measured $mediaId: " +
                    (structure?.let { "${it.bpm} BPM, intro ends ${it.introEndMs} ms, last chorus ends ${it.lastChorusEndMs} ms, music ends ${it.musicEndMs} ms" } ?: "nothing usable"),
            )
            return Measured(bars, structure)
        } finally {
            progress.clear(mediaId)
            levels.delete()
            pcm.delete()
        }
    }

    /** ao_pcm's output: raw 16-bit little-endian samples, no header (`ao-pcm-waveheader=no`). */
    private fun readPcm(file: File): ShortArray {
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(buffer.remaining()).also { buffer.get(it) }
    }

    /** Plays [url] through a silent handle that writes one RMS line per bar to [levels] and the audio to [pcm]. */
    private suspend fun decode(
        url: String,
        levels: File,
        pcm: File,
        onProgress: suspend (FloatArray) -> Unit,
    ): Boolean {
        val lib = MpvLibrary.INSTANCE ?: return false
        val ctx = lib.mpv_create() ?: return false
        try {
            fun option(
                name: String,
                value: String,
            ): Boolean {
                val rc = lib.mpv_set_option_string(ctx, name, value)
                if (rc < 0) Logger.w(TAG, "mpv_set_option_string($name) failed: ${lib.mpv_error_string(rc)}")
                return rc >= 0
            }
            option("terminal", "no")
            // Absent from lua-less builds, where it is off anyway.
            lib.mpv_set_option_string(ctx, "ytdl", "no")
            option("vid", "no")
            option("vo", "null")
            if (!option("ao", "pcm")) return false
            if (!option("ao-pcm-file", pcm.absolutePath)) return false
            option("ao-pcm-waveheader", "no")
            // Same reconnect behaviour as the playing handles (see MpvPlayer.create).
            option("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_delay_max=30")
            val graph =
                "lavfi=[aresample=$SAMPLE_RATE,aformat=sample_fmts=s16:channel_layouts=mono," +
                    "asetnsamples=n=$SAMPLES_PER_BAR:p=0,astats=metadata=1:reset=1," +
                    "ametadata=mode=print:key=lavfi.astats.1.RMS_level:direct=1:file=${lavfiEscapePath(levels.absolutePath)}]"
            if (!option("af", graph)) return false
            if (lib.mpv_initialize(ctx) < 0) return false
            if (lib.mpv_command(ctx, arrayOf("loadfile", url, null)) < 0) return false
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            var nextProgressAt = 0L
            var levelsLength = 0L
            var publishedBars = 0
            while (System.currentTimeMillis() < deadline) {
                currentCoroutineContext().ensureActive()
                val now = System.currentTimeMillis()
                if (now >= nextProgressAt) {
                    nextProgressAt = now + 250L
                    val length = levels.length()
                    if (length > levelsLength) {
                        levelsLength = length
                        parse(levels)?.let { bars ->
                            if (bars.size > publishedBars) {
                                onProgress(bars)
                                publishedBars = bars.size
                            }
                        }
                    }
                }
                val pointer = lib.mpv_wait_event(ctx, 0.25) ?: continue
                val event = MpvEvent(pointer).apply { read() }
                when (event.event_id) {
                    MpvEventId.END_FILE -> {
                        val endFile = event.data?.let { MpvEventEndFile(it).apply { read() } }
                        if (endFile?.reason == MpvEndFileReason.EOF) return true
                        // An error here is how a slice without the three filters shows up, too.
                        Logger.w(TAG, "Measuring stopped early: reason ${endFile?.reason}, ${endFile?.error?.let { lib.mpv_error_string(it) }}")
                        return false
                    }
                    MpvEventId.SHUTDOWN -> return false
                }
            }
            Logger.w(TAG, "Gave up measuring after ${TIMEOUT_MS / 1000} s")
            return false
        } finally {
            // Tear down before the final parse/cache write; incomplete output is never cached.
            lib.mpv_terminate_destroy(ctx)
        }
    }

    /**
     * Reads ametadata's print output: a `frame:… pts_time:<s>` line, then `…RMS_level=<dBFS>` for that
     * frame ("-inf" for digital silence). Each frame is one bar, placed by its own time.
     */
    private fun parse(levels: File): FloatArray? {
        if (!levels.isFile) return null
        val values = HashMap<Int, Float>()
        var time = -1.0
        // The writer can be in the middle of a line: only complete records count as loaded audio.
        levels.readText().substringBeforeLast('\n', "").lineSequence().forEach { line ->
            val pts = line.substringAfter("pts_time:", "").trim()
            if (pts.isNotEmpty()) {
                time = pts.toDoubleOrNull() ?: -1.0
                return@forEach
            }
            val db = line.substringAfter("RMS_level=", "").trim()
            if (db.isNotEmpty() && time >= 0.0) {
                values[(time * WAVEFORM_BARS_PER_SECOND).roundToInt()] = db.toDoubleOrNull()?.let { 10.0.pow(it / 20.0).toFloat() } ?: 0f
            }
        }
        if (values.isEmpty()) return null
        val rms = FloatArray(values.keys.max() + 1) { values[it] ?: 0f }
        val loudest = rms.max().takeIf { it > 0f } ?: return null
        return FloatArray(rms.size) { (rms[it] / loudest).coerceIn(MIN_LEVEL, 1f) }
    }
}
