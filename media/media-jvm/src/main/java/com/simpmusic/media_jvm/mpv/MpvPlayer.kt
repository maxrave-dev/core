package com.simpmusic.media_jvm.mpv

import com.maxrave.logger.Logger
import com.sun.jna.Memory
import com.sun.jna.Pointer
import java.awt.Component

private const val TAG = "MpvPlayer"

/**
 * Event callbacks for a single [MpvPlayer], shaped after vlcj's `MediaPlayerEventAdapter` so the
 * adapter's event-handling logic is a straight port.
 *
 * These are invoked from the player's own event-pump thread. Unlike VLC — whose callbacks come
 * from a native thread that deadlocks if you call `stop()`/`release()` on it — mpv's event
 * delivery is pull-based, so re-entering the API from here is safe. Implementations still
 * dispatch onto the service coroutine scope to keep the state machine serialized.
 */
open class MpvPlayerEventAdapter {
    /** `MPV_EVENT_END_FILE` with `MPV_END_FILE_REASON_EOF`. */
    open fun finished(player: MpvPlayer) {}

    /** `MPV_EVENT_END_FILE` with `MPV_END_FILE_REASON_ERROR`. */
    open fun error(player: MpvPlayer) {}

    /** `MPV_EVENT_PLAYBACK_RESTART` combined with observed `pause` == false. */
    open fun playing(player: MpvPlayer) {}

    /** Observed `pause` == true. */
    open fun paused(player: MpvPlayer) {}

    /** `MPV_EVENT_END_FILE` with `MPV_END_FILE_REASON_STOP`. */
    open fun stopped(player: MpvPlayer) {}

    /** Observed `time-pos`, converted to milliseconds. */
    open fun timeChanged(
        player: MpvPlayer,
        newTimeMs: Long,
    ) {}

    /** Observed `duration`, converted to milliseconds. */
    open fun lengthChanged(
        player: MpvPlayer,
        newLengthMs: Long,
    ) {}

    /** Observed `cache-buffering-state` (0-100). Same semantics as vlcj's `buffering(pct)`. */
    open fun buffering(
        player: MpvPlayer,
        newCache: Float,
    ) {}

    /** `MPV_EVENT_START_FILE`. */
    open fun opening(player: MpvPlayer) {}
}

/**
 * Thin wrapper around one libmpv handle — the mpv counterpart of `VlcPlayer`.
 *
 * One handle plays one media item at a time, exactly like a vlcj `MediaPlayer`, which lets the
 * adapter keep VLC's model of "a player instance per queue entry" (current / secondary /
 * precached) unchanged.
 *
 * ## Unit conversions are contained here
 * The public surface of this class speaks the SAME units as `VlcPlayer` so that the ported
 * business logic needs no edits:
 *  - [time] / [length] / [seekTo] are MILLISECONDS (mpv's `time-pos` / `duration` are seconds).
 *  - [setVolume] takes 0..100 (mpv's `volume` is natively 0..100, so this is 1:1; VLC accepted
 *    0..200 but the adapter only ever drove it to 100).
 */
class MpvPlayer private constructor(
    private val ctx: Pointer,
    private val lib: MpvLibrary,
    /**
     * The video render target, or null for an audio-only handle. Populated with an
     * [MpvVideoSurfacePanel] driving mpv's software render API — the counterpart of the
     * `VlcVideoSurfacePanel` that vlcj rendered into.
     */
    val videoSurface: Component? = null,
) {
    /** Same instance as [videoSurface]; typed so teardown can reach [MpvVideoSurfacePanel.detach]. */
    private var videoPanel: MpvVideoSurfacePanel? = null
    companion object {
        /** Userdata tag for every `mpv_observe_property` registration; we dispatch by name. */
        private const val OBSERVE_USERDATA = 1L

        /** Scratch buffer for get/set_property with a native format. One per calling thread. */
        private val scratch = ThreadLocal.withInitial { Memory(8) }

        /**
         * Create and initialize a libmpv handle.
         *
         * @param audioOnly disables video decoding and the video output entirely. When false, an
         *   [MpvVideoSurfacePanel] is created and its render context is attached before any file
         *   is loaded, as render.h requires.
         * @param networkCacheSeconds mpv's `cache-secs`. VLC's `--network-caching` was expressed
         *   in milliseconds (10000 / 15000); mpv's equivalent is in seconds.
         * @return null if libmpv is unavailable or the handle could not be initialized.
         */
        fun create(
            audioOnly: Boolean = true,
            networkCacheSeconds: Int = 10,
        ): MpvPlayer? {
            val lib = MpvLibrary.INSTANCE ?: return null
            val ctx = lib.mpv_create()
            if (ctx == null) {
                // Historically this meant a non-C LC_NUMERIC; MpvLibrary now forces that at load
                // time, so a null here is a genuine failure worth logging loudly.
                Logger.e(TAG, "mpv_create() returned null")
                return null
            }

            // Options must be set BEFORE mpv_initialize().
            fun option(
                name: String,
                value: String,
            ) {
                val rc = lib.mpv_set_option_string(ctx, name, value)
                if (rc < 0) {
                    Logger.w(TAG, "mpv_set_option_string($name=$value) failed: ${lib.mpv_error_string(rc)}")
                }
            }

            // VLC "--quiet" / "--no-video-title-show" / "--no-metadata-network-access" have no
            // direct mpv analogue; disabling the terminal covers all of their observable effect.
            //
            // MUST stay "no". With terminal=yes, mpv wires up terminal output — and this build of
            // libmpv ships the `sixel` feature (terminal image output), which pulled libsixel into
            // play and aborted the whole JVM with
            //   ../src/allocator.c:139: sixel_allocator_malloc: Assertion `allocator' failed.
            // Also covers what VLC's "--quiet" / "--no-video-title-show" did.
            option("terminal", "no")
            // We resolve stream URLs ourselves via StreamRepository, so mpv must never invoke its
            // ytdl_hook Lua script (seen firing as "Running hook: ytdl_hook/on_load"). Leaving it on
            // means mpv shells out to youtube-dl/yt-dlp behind our back on every loadfile.
            option("ytdl", "no")
            // Keep the core alive across end-of-file so one handle can be reloaded, and so EOF
            // surfaces as MPV_EVENT_END_FILE instead of MPV_EVENT_SHUTDOWN.
            option("idle", "yes")
            option("audio-client-name", "SimpMusic")

            // VLC "--network-caching=10000" / ":network-caching=15000".
            option("cache", "yes")
            option("cache-secs", networkCacheSeconds.toString())

            // VLC ":http-reconnect".
            option(
                "stream-lavf-o",
                "reconnect=1,reconnect_streamed=1,reconnect_delay_max=30",
            )

            // ALWAYS pin the video output explicitly, on every branch.
            //
            // mpv picks the VO during mpv_initialize(). Leaving it unset lets mpv auto-probe, and
            // in a headless JVM process it can land on a terminal-graphics driver — which is
            // exactly what happened: it selected `sixel`, and libsixel aborted the whole JVM with
            //   ../src/allocator.c:139: sixel_allocator_malloc: Assertion `allocator' failed.
            // (SIGABRT, exit 134) during ordinary music playback. Creating the render context
            // afterwards does NOT retroactively change a VO that mpv_initialize already chose.
            //
            // `libmpv` is the VO that backs the render API — v0.37.0 DOCS/man/vo.rst:561:
            //   ``libmpv``
            //       For use with libmpv direct embedding. ...
            //       (See ``<mpv/render.h>``.)
            // Note `--vo=<driver>` takes a SINGLE driver, not a priority list (unlike `--vd`), so
            // a "libmpv,null" fallback chain is not available here.

            // Unlike option(), a failure here is fatal rather than a warning — see below.
            fun requiredOption(
                name: String,
                value: String,
            ): Boolean {
                val rc = lib.mpv_set_option_string(ctx, name, value)
                if (rc < 0) {
                    Logger.e(TAG, "mpv_set_option_string($name=$value) failed: ${lib.mpv_error_string(rc)}")
                    return false
                }
                return true
            }

            val voPinned =
                if (audioOnly) {
                    // VLC ":no-video".
                    option("vid", "no")
                    requiredOption("vo", "null")
                } else {
                    requiredOption("vo", "libmpv")
                }
            if (!voPinned) {
                // Never hand an unpinned VO to mpv_initialize: auto-probing is what selected the
                // terminal-graphics driver that aborted the process. Failing to create a player is
                // recoverable; a SIGABRT is not.
                Logger.e(TAG, "Refusing to initialize mpv without a pinned video output")
                lib.mpv_terminate_destroy(ctx)
                return null
            }

            val rc = lib.mpv_initialize(ctx)
            if (rc < 0) {
                Logger.e(TAG, "mpv_initialize failed: ${lib.mpv_error_string(rc)}")
                lib.mpv_terminate_destroy(ctx)
                return null
            }

            // The render context must exist before the first loadfile — render.h: "The renderer
            // needs to be created with mpv_render_context_create() before you start playback (or
            // otherwise cause a VO to be created)", and "Video initialization will fail if the
            // render context was not initialized yet ... or it will revert to a VO that creates
            // its own window."
            //
            // Creating it here (post-initialize, pre-loadfile) is correct: mpv_initialize() only
            // applies options, while the VO is instantiated when a file with video is loaded.
            var panel: MpvVideoSurfacePanel? = null
            if (!audioOnly) {
                val created = MpvVideoSurfacePanel()
                if (created.attach(ctx)) {
                    panel = created
                } else {
                    // vo=libmpv is now pinned but has no render context behind it. Disable video
                    // decoding outright so no VO is ever needed, and point the VO at the null sink
                    // as a second line of defence. vid=no is the load-bearing one: it is settable
                    // at runtime and guarantees mpv can never reach a terminal-graphics driver.
                    Logger.e(TAG, "Software render context unavailable; continuing without video")
                    lib.mpv_set_property_string(ctx, "vid", "no")
                    lib.mpv_set_property_string(ctx, "vo", "null")
                }
            }

            return MpvPlayer(ctx, lib, panel).also {
                it.videoPanel = panel
                it.start()
            }
        }
    }

    @Volatile
    var isReleased = false
        private set

    @Volatile
    private var eventListener: MpvPlayerEventAdapter? = null

    @Volatile
    private var pumpRunning = true

    private var pumpThread: Thread? = null

    // ---- play/pause edge detection (see maybeEmitPlayState) ----

    @Volatile
    private var pausedFlag = true

    @Volatile
    private var playbackRestarted = false

    @Volatile
    private var lastEmittedPlaying: Boolean? = null

    // ================= setup =================

    private fun start() {
        // MPV_FORMAT_NONE means "notify me, don't carry a value"; we ask for real values instead.
        observe("time-pos", MpvFormat.DOUBLE)
        observe("duration", MpvFormat.DOUBLE)
        observe("pause", MpvFormat.FLAG)
        observe("cache-buffering-state", MpvFormat.INT64)

        pumpThread =
            Thread({ pumpLoop() }, "Mpv-Event-Pump").apply {
                isDaemon = true
                start()
            }
    }

    private fun observe(
        name: String,
        format: Int,
    ) {
        val rc = lib.mpv_observe_property(ctx, OBSERVE_USERDATA, name, format)
        if (rc < 0) {
            Logger.w(TAG, "mpv_observe_property($name) failed: ${lib.mpv_error_string(rc)}")
        }
    }

    fun setEventListener(listener: MpvPlayerEventAdapter?) {
        eventListener = listener
    }

    // ================= event pump =================

    /**
     * Owns this handle's `mpv_wait_event` loop.
     *
     * mpv's threading rules forbid calling any API function from `mpv_set_wakeup_callback`, so no
     * business logic runs there — this dedicated pull-based thread replaces VLC's push callbacks
     * entirely. The short timeout lets [release] shut the loop down promptly.
     */
    private fun pumpLoop() {
        while (pumpRunning) {
            val eventPtr =
                try {
                    lib.mpv_wait_event(ctx, 0.1)
                } catch (e: Throwable) {
                    Logger.e(TAG, "mpv_wait_event threw: ${e.message}")
                    return
                } ?: continue

            val event =
                try {
                    MpvEvent(eventPtr).apply { read() }
                } catch (e: Throwable) {
                    Logger.w(TAG, "Failed to read mpv_event: ${e.message}")
                    continue
                }

            if (event.event_id == MpvEventId.NONE) continue

            try {
                dispatch(event)
            } catch (e: Throwable) {
                Logger.e(TAG, "Error dispatching mpv event ${event.event_id}: ${e.message}")
            }

            if (event.event_id == MpvEventId.SHUTDOWN) {
                pumpRunning = false
                return
            }
        }
    }

    private fun dispatch(event: MpvEvent) {
        val listener = eventListener
        when (event.event_id) {
            MpvEventId.START_FILE -> {
                playbackRestarted = false
                lastEmittedPlaying = null
                listener?.opening(this)
            }

            MpvEventId.END_FILE -> {
                playbackRestarted = false
                lastEmittedPlaying = null
                val dataPtr = event.data
                val reason =
                    if (dataPtr == null) {
                        MpvEndFileReason.QUIT
                    } else {
                        MpvEventEndFile(dataPtr).apply { read() }.reason
                    }
                when (reason) {
                    MpvEndFileReason.EOF -> listener?.finished(this)
                    MpvEndFileReason.ERROR -> listener?.error(this)
                    MpvEndFileReason.STOP -> listener?.stopped(this)
                    // QUIT / REDIRECT carry no meaning for this backend.
                    else -> Unit
                }
            }

            MpvEventId.PLAYBACK_RESTART -> {
                // Also fires after every seek, so it can't stand in for vlcj's playing() on its
                // own — it only marks "output has (re)started"; the pause flag decides the rest.
                playbackRestarted = true
                maybeEmitPlayState()
            }

            MpvEventId.PROPERTY_CHANGE -> {
                val dataPtr = event.data ?: return
                val prop = MpvEventProperty(dataPtr).apply { read() }
                val name = prop.name?.getString(0) ?: return
                // format == MPV_FORMAT_NONE (and data == null) means the value was unavailable.
                val valuePtr = prop.data ?: return
                when (name) {
                    "time-pos" ->
                        if (prop.format == MpvFormat.DOUBLE) {
                            listener?.timeChanged(this, secondsToMs(valuePtr.getDouble(0)))
                        }

                    "duration" ->
                        if (prop.format == MpvFormat.DOUBLE) {
                            listener?.lengthChanged(this, secondsToMs(valuePtr.getDouble(0)))
                        }

                    "pause" ->
                        if (prop.format == MpvFormat.FLAG) {
                            pausedFlag = valuePtr.getInt(0) != 0
                            maybeEmitPlayState()
                        }

                    "cache-buffering-state" ->
                        if (prop.format == MpvFormat.INT64) {
                            listener?.buffering(this, valuePtr.getLong(0).toFloat())
                        }
                }
            }
        }
    }

    /**
     * Emit vlcj-equivalent `playing()` / `paused()` edges.
     *
     * mpv has no single "now playing" event: `MPV_EVENT_PLAYBACK_RESTART` fires on seeks too, and
     * the `pause` property flips before output actually resumes. Requiring both, and deduplicating
     * on the last emitted value, reproduces VLC's one-shot semantics.
     */
    private fun maybeEmitPlayState() {
        if (!playbackRestarted) return
        val playing = !pausedFlag
        if (lastEmittedPlaying == playing) return
        lastEmittedPlaying = playing
        if (playing) eventListener?.playing(this) else eventListener?.paused(this)
    }

    // ================= transport =================

    /**
     * Load [url] and begin playback, or load it held at the first frame when [startPaused].
     *
     * mpv collapses vlcj's `media().play()`, `media().startPaused()` and `media().prepare()` into
     * one `loadfile` — the `pause` property decides which of the three it behaves as.
     *
     * Deliberately uses the 3-argument form `loadfile <url> replace`, which is unambiguous on
     * every mpv version. The trailing parameters are NOT stable across releases:
     *  - 0.37.0 (our target) documents `loadfile <url> [<flags> [<options>]]` — the third
     *    argument is the per-file OPTION STRING.
     *  - 0.38.0 inserted `<index>` ahead of it: `loadfile <url> [<flags> [<index> [<options>]]]`.
     *
     * So anything past `<flags>` would mean different things on different builds. Per-file options
     * are unnecessary here regardless: one handle serves exactly one media item, so they are set
     * on the handle itself in [create] — the same role VLC's `:option` media arguments played.
     */
    fun loadFile(
        url: String,
        startPaused: Boolean,
    ) {
        if (isReleased) return
        setPropertyString("pause", if (startPaused) "yes" else "no")
        command("loadfile", url, "replace")
    }

    fun play() {
        if (isReleased) return
        setPropertyString("pause", "no")
    }

    fun pause() {
        if (isReleased) return
        setPropertyString("pause", "yes")
    }

    fun stop() {
        if (isReleased) return
        command("stop")
    }

    /** @param volume 0..100, matching `VlcPlayer.setVolume`. mpv's `volume` is natively 0..100. */
    fun setVolume(volume: Int) {
        if (isReleased) return
        setPropertyDouble("volume", volume.coerceIn(0, 100).toDouble())
    }

    fun setMute(mute: Boolean) {
        if (isReleased) return
        setPropertyString("mute", if (mute) "yes" else "no")
    }

    fun setRate(rate: Float) {
        if (isReleased) return
        setPropertyDouble("speed", rate.toDouble())
    }

    /**
     * Repeat the current file indefinitely.
     *
     * mpv's `loop-file` property (v0.37.0 options.rst: `--loop-file=<N|inf|no>`, *"inf means
     * forever"*). Preferred over re-issuing `loadfile` when end-of-file arrives — the VLC-era
     * approach — because looping natively replays from the demuxer cache instead of refetching
     * the whole URL on every repeat.
     */
    fun setLooping(loop: Boolean) {
        if (isReleased) return
        setPropertyString("loop-file", if (loop) "inf" else "no")
    }

    /** @param timeMs milliseconds, matching `VlcPlayer.seekTo`. mpv's `time-pos` is seconds. */
    fun seekTo(timeMs: Long) {
        if (isReleased) return
        setPropertyDouble("time-pos", timeMs / 1000.0)
    }

    /** Current position in milliseconds, or 0. Mirrors `VlcPlayer.time`. */
    val time: Long
        get() = if (isReleased) 0L else secondsToMs(getPropertyDouble("time-pos"))

    /** Duration in milliseconds, or 0. Mirrors `VlcPlayer.length`. */
    val length: Long
        get() = if (isReleased) 0L else secondsToMs(getPropertyDouble("duration"))

    // ================= teardown =================

    /**
     * Stop the pump and render threads, then destroy the handle.
     *
     * Ordering matters twice over:
     *  - `mpv_wait_event` must not run against a handle that `mpv_terminate_destroy` is tearing
     *    down, so the pump is stopped and joined first.
     *  - render.h: *"You must free the context with mpv_render_context_free() before the mpv core
     *    is destroyed. If this doesn't happen, undefined behavior will result."* — so
     *    [MpvVideoSurfacePanel.detach] runs before `mpv_terminate_destroy`.
     *
     * The whole sequence runs off-thread because both `detach` (which joins the render thread) and
     * `mpv_terminate_destroy` (which blocks until the core is gone) would otherwise stall the
     * single service thread that drives playback.
     */
    fun release() {
        if (isReleased) return
        isReleased = true
        eventListener = null
        pumpRunning = false

        val thread = pumpThread
        pumpThread = null
        val panel = videoPanel
        videoPanel = null

        Thread({
            try {
                thread?.join(1000)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            try {
                panel?.detach()
            } catch (e: Throwable) {
                Logger.w(TAG, "Error detaching video surface: ${e.message}")
            }
            try {
                lib.mpv_terminate_destroy(ctx)
            } catch (e: Throwable) {
                Logger.w(TAG, "Error destroying mpv handle: ${e.message}")
            }
        }, "Mpv-Release").apply { isDaemon = true }.start()
    }

    // ================= native helpers =================

    private fun command(vararg args: String) {
        // mpv_command takes a NULL-terminated char** — JNA does not append the terminator.
        val argv = arrayOfNulls<String>(args.size + 1)
        args.forEachIndexed { i, a -> argv[i] = a }
        val rc =
            try {
                lib.mpv_command(ctx, argv)
            } catch (e: Throwable) {
                Logger.e(TAG, "mpv_command(${args.firstOrNull()}) threw: ${e.message}")
                return
            }
        if (rc < 0) {
            Logger.w(TAG, "mpv_command(${args.joinToString(" ")}) failed: ${lib.mpv_error_string(rc)}")
        }
    }

    private fun setPropertyString(
        name: String,
        value: String,
    ) {
        try {
            val rc = lib.mpv_set_property_string(ctx, name, value)
            if (rc < 0) {
                Logger.w(TAG, "set $name=$value failed: ${lib.mpv_error_string(rc)}")
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "set $name threw: ${e.message}")
        }
    }

    private fun setPropertyDouble(
        name: String,
        value: Double,
    ) {
        try {
            val mem = scratch.get()
            mem.setDouble(0, value)
            val rc = lib.mpv_set_property(ctx, name, MpvFormat.DOUBLE, mem)
            if (rc < 0) {
                Logger.w(TAG, "set $name=$value failed: ${lib.mpv_error_string(rc)}")
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "set $name threw: ${e.message}")
        }
    }

    /** @return the property value, or 0.0 when unavailable (e.g. nothing loaded yet). */
    private fun getPropertyDouble(name: String): Double =
        try {
            val mem = scratch.get()
            if (lib.mpv_get_property(ctx, name, MpvFormat.DOUBLE, mem) < 0) 0.0 else mem.getDouble(0)
        } catch (e: Throwable) {
            Logger.e(TAG, "get $name threw: ${e.message}")
            0.0
        }
}

/** mpv reports times in seconds; the whole player stack above speaks milliseconds. */
private fun secondsToMs(seconds: Double): Long =
    if (seconds.isNaN() || seconds.isInfinite() || seconds <= 0.0) 0L else (seconds * 1000.0).toLong()
