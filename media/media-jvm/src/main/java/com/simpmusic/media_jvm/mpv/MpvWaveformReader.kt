package com.simpmusic.media_jvm.mpv

import com.maxrave.domain.mediaservice.player.WAVEFORM_BARS_PER_SECOND
import com.maxrave.logger.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Builds the SoundCloud style's waveform on Desktop, matching Android's WaveformReader: one level per
 * 1 / [WAVEFORM_BARS_PER_SECOND] s, each 0..1 against the loudest bar, kept under ~/.simpmusic/waveforms.
 *
 * mpv shares no cache between handles, so the track's audio is played once more through a second,
 * silent handle: `ao=null` with `ao-null-untimed` decodes as fast as the stream arrives. A lavfi chain
 * resamples it to 8 kHz mono, cuts it into one frame per bar, measures each frame's RMS with `astats`,
 * and `ametadata` prints the values to a file that is read back once the file has ended.
 *
 * The chain needs `asetnsamples`, `astats` and `ametadata`. The macOS and Windows slices carry every
 * FFmpeg filter; the Linux slice carries only its whitelist (scripts/mpv-linux/Dockerfile). On a slice
 * without them the graph fails, nothing is written, and the caller gets null: a flat line.
 */
internal object MpvWaveformReader {
    private const val TAG = "Waveform"
    private const val SAMPLE_RATE = 8_000
    private val SAMPLES_PER_BAR = (SAMPLE_RATE / WAVEFORM_BARS_PER_SECOND).roundToInt()

    // A stream that has not ended in this long is not worth holding a decoder for.
    private const val TIMEOUT_MS = 120_000L

    // Silence still draws a sliver, as on Android, so the line never breaks.
    private const val MIN_LEVEL = 0.04f
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
        sourceUrl: suspend () -> String?,
    ): FloatArray? =
        withContext(Dispatchers.IO) {
            val name = mediaId.replace(UNSAFE_FILE_CHARS, "_")
            val stored = File(dir, "$name.wf")
            if (stored.isFile && stored.length() > 0) {
                val bytes = stored.readBytes()
                return@withContext FloatArray(bytes.size) { (bytes[it].toInt() and 0xFF) / 255f }
            }
            val url = sourceUrl()
            if (url == null) {
                Logger.d(TAG, "No playable audio URL for $mediaId yet")
                return@withContext null
            }
            val levels = File(dir, "$name.levels")
            try {
                levels.delete()
                if (!measure(url, levels)) return@withContext null
                val bars = parse(levels)
                if (bars == null) {
                    Logger.w(TAG, "Measured $mediaId but read no levels back")
                    return@withContext null
                }
                stored.writeBytes(ByteArray(bars.size) { (bars[it] * 255f).toInt().coerceIn(0, 255).toByte() })
                bars
            } finally {
                levels.delete()
            }
        }

    /** Plays [url] through a silent handle that writes one RMS line per bar to [levels]. */
    private suspend fun measure(
        url: String,
        levels: File,
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
            option("ao", "null")
            option("ao-null-untimed", "yes")
            // Same reconnect behaviour as the playing handles (see MpvPlayer.create).
            option("stream-lavf-o", "reconnect=1,reconnect_streamed=1,reconnect_delay_max=30")
            val graph =
                "lavfi=[aresample=$SAMPLE_RATE,aformat=channel_layouts=mono," +
                    "asetnsamples=n=$SAMPLES_PER_BAR:p=0,astats=metadata=1:reset=1," +
                    "ametadata=mode=print:key=lavfi.astats.1.RMS_level:file=${lavfiEscapePath(levels.absolutePath)}]"
            if (!option("af", graph)) return false
            if (lib.mpv_initialize(ctx) < 0) return false
            if (lib.mpv_command(ctx, arrayOf("loadfile", url, null)) < 0) return false
            val deadline = System.currentTimeMillis() + TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                currentCoroutineContext().ensureActive()
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
            // Tears the filter graph down too, which is what flushes [levels] to disk.
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
        levels.forEachLine { line ->
            val pts = line.substringAfter("pts_time:", "").trim()
            if (pts.isNotEmpty()) {
                time = pts.toDoubleOrNull() ?: -1.0
                return@forEachLine
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
