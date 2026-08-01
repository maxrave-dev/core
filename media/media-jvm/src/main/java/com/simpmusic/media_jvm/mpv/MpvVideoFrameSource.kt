package com.simpmusic.media_jvm.mpv

import com.maxrave.logger.Logger
import com.sun.jna.Memory
import com.sun.jna.Pointer
import com.sun.jna.ptr.PointerByReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

private const val TAG = "MpvVideoFrameSource"

/**
 * Produces mpv video frames through the software render API as immutable [BufferedImage]
 * snapshots that Compose draws directly.
 *
 * Successor of `MpvVideoSurfacePanel`, which blitted the same frames onto a Swing `JPanel`
 * embedded in Compose via `SwingPanel`. SwingPanel is a heavyweight AWT overlay: it sits above
 * every Compose node regardless of z-order, repositions one frame late while scrolling (the
 * flicker that exposes the transparent window behind it), and — being an AWT component — can
 * only ever have ONE parent, so two screens composing the player fought over the panel and the
 * loser went black. Publishing plain frames removes the AWT dependency entirely: any number of
 * composables can collect [frames] and draw them as ordinary images.
 *
 * mpv's side is unchanged: `MPV_RENDER_API_TYPE_SW` — mpv decodes, scales and letterboxes into a
 * buffer we hand it, and we copy that buffer out ourselves. render.h calls this renderer
 * "extremely simple (but slow)" and it is CPU-bound and single-threaded, which is the same
 * performance class as the vlcj render-callback path it replaced.
 *
 * ## Lifecycle (render.h "Context and handle lifecycle")
 * [attach] must run after `mpv_initialize` but BEFORE the first `loadfile`: *"The renderer needs to
 * be created with mpv_render_context_create() before you start playback"*, and *"Video
 * initialization will fail if the render context was not initialized yet ... or it will revert to a
 * VO that creates its own window."* [detach] must run before `mpv_terminate_destroy`: *"You must
 * free the context with mpv_render_context_free() before the mpv core is destroyed."*
 *
 * ## Threading (render.h "Threading")
 * The update callback fires on a foreign thread, and render.h forbids calling any `mpv_render_*`
 * function from inside it, so [updateCallback] only signals. All rendering happens on a dedicated
 * thread owned by this source, which — as render.h requires of a render thread — calls no libmpv
 * function other than `mpv_render_*`. Consumers only ever see finished snapshots via [frames].
 */
class MpvVideoFrameSource {
    /**
     * mpv's SW pixel format, chosen to match `BufferedImage.TYPE_INT_RGB` with no per-pixel
     * swizzle.
     *
     * render.h defines these format names by BYTE ORDER AT INCREASING ADDRESSES: *"component bytes
     * with increasing address from left to right (e.g. \"rgb0\" has r at address 0)"*. So `bgr0`
     * lays out B@0, G@1, R@2, unused@3.
     *
     * Java2D's `TYPE_INT_RGB` packs a pixel into one int as `0x00RRGGBB`. On a little-endian JVM
     * — every platform Java desktop ships on — that int occupies memory as B@0, G@1, R@2, 0@3.
     * Identical. A bulk `Pointer.read` into an `int[]` therefore needs no channel fixup;
     * picking `rgb0` here instead is exactly what produces the classic red/blue-swapped video.
     *
     * `TYPE_INT_RGB` rather than `TYPE_INT_ARGB`, deliberately: render.h warns the `0` component
     * *"contains uninitialized garbage (often the value 0, but not necessarily)"*. In an ARGB
     * image that byte is the alpha channel, so a garbage-zero would make every frame fully
     * transparent. `TYPE_INT_RGB` ignores the high byte entirely.
     */
    private val swFormat = "bgr0"

    /** Bytes per pixel for [swFormat] — render.h: "4 bytes per pixel RGB ... Pixel alignment size: 4 bytes". */
    private val bytesPerPixel = 4

    /**
     * render.h asks that *"Both stride and pointer value should be a multiple of 64 to facilitate
     * fast SIMD operation"*, warning that lower alignment "might trigger slower code paths, and in
     * the worst case, will copy the entire target frame".
     */
    private val alignment = 64L

    private val lib: MpvLibrary? = MpvLibrary.INSTANCE

    @Volatile
    private var renderCtx: Pointer? = null

    /** Guards [surface] so [detach] cannot null it out mid-copy on the render thread. */
    private val surfaceLock = Any()

    @Volatile
    private var surface: Surface? = null

    /** Latest size reported via [setTargetSize]; the render thread reallocates to match. */
    @Volatile
    private var requestedWidth = 0

    @Volatile
    private var requestedHeight = 0

    private val _frames = MutableStateFlow<BufferedImage?>(null)

    /**
     * Latest finished video frame, already scaled and letterboxed by mpv to the size last given
     * via [setTargetSize]. Every emission is a fresh snapshot that is never written to again, so
     * consumers may convert or draw it on any thread without locking. Null until the first frame
     * arrives, and null again after [detach] so the UI can fall back to the artwork.
     */
    val frames: StateFlow<BufferedImage?> = _frames.asStateFlow()

    /**
     * Signalled by [updateCallback] (a foreign thread) and awaited by the render thread.
     *
     * A semaphore rather than a lock: the update callback must never block, and render.h forbids
     * it from doing anything but signalling.
     */
    private val frameSignal = Semaphore(0)

    @Volatile
    private var running = false

    private var renderThread: Thread? = null

    /** Native "sw" string for MPV_RENDER_PARAM_API_TYPE; must outlive the create() call. */
    private val apiTypeMem =
        Memory(MPV_RENDER_API_TYPE_SW.toByteArray(Charsets.UTF_8).size + 1L).apply {
            setString(0, MPV_RENDER_API_TYPE_SW)
        }

    /** Native format string for MPV_RENDER_PARAM_SW_FORMAT; must outlive every render() call. */
    private val formatMem =
        Memory(swFormat.toByteArray(Charsets.UTF_8).size + 1L).apply {
            setString(0, swFormat)
        }

    /**
     * Strongly held so JNA cannot collect it while mpv still holds the function pointer — the same
     * hazard `VlcVideoSurfacePanel` guarded against for its vlcj callbacks.
     */
    private val updateCallback =
        MpvRenderUpdateFn {
            // render.h forbids calling ANY mpv function here. Signal only.
            frameSignal.release()
        }

    /**
     * One allocated render target. Immutable; swapped wholesale on resize so the render thread and
     * [detach] never disagree about dimensions.
     *
     * [pixels] is [bufferWidth] ints per row rather than [width]: the stride is padded up to
     * [alignment], and making the staging buffer exactly stride-wide keeps the frame contiguous so
     * it can be copied out of native memory in a single bulk read. Only the leftmost [width]
     * columns are ever published — render.h leaves the padding between `(w, y)` and `(0, y + 1)`
     * explicitly unspecified.
     */
    private class Surface(
        val width: Int,
        val height: Int,
        val bufferWidth: Int,
        /** Kept for logging/diagnostics; the value mpv reads lives in [strideMem]. */
        @Suppress("unused") val strideBytes: Long,
        /** 64-byte-aligned view into [backing]; `Memory.share` hands back a plain [Pointer]. */
        val pixelBuffer: Pointer,
        /** Held only to keep the allocation that [pixelBuffer] points into alive. */
        @Suppress("unused") val backing: Memory,
        /** Stride-wide staging row buffer the native frame is bulk-read into. */
        val pixels: IntArray,
        // DO NOT DELETE sizeMem / strideMem because they look unused. [renderParams] stores raw
        // pointers INTO these two allocations, which mpv dereferences on every render call.
        // Dropping the references would let JNA free them out from under native code.
        /** Backing store for MPV_RENDER_PARAM_SW_SIZE (`int[2]`). */
        @Suppress("unused") val sizeMem: Memory,
        /** Backing store for MPV_RENDER_PARAM_SW_STRIDE (`size_t`). */
        @Suppress("unused") val strideMem: Memory,
        /** The packed, `type == 0`-terminated parameter array handed to `mpv_render_context_render`. */
        val renderParams: Memory,
    )

    /**
     * Report the size the video should be rendered at, in physical pixels. Replaces the Swing
     * `componentResized` listener — Compose calls this from `onSizeChanged`. The render thread
     * reallocates its target and repaints at the new size on the next wake-up.
     */
    fun setTargetSize(
        width: Int,
        height: Int,
    ) {
        if (width <= 0 || height <= 0) return
        if (width == requestedWidth && height == requestedHeight) return
        requestedWidth = width
        requestedHeight = height
        frameSignal.release()
    }

    /**
     * Create the mpv render context for [ctx] and start the render thread.
     *
     * @return true when video rendering is live. On false the caller must fall back to audio-only
     *   (force `vid=no`), otherwise mpv would open its own window at the first `loadfile`.
     */
    fun attach(ctx: Pointer): Boolean {
        val library = lib ?: return false
        if (renderCtx != null) return true

        val params =
            buildRenderParams(
                listOf(
                    // API_TYPE takes the string pointer directly ("Type: char*").
                    MpvRenderParamType.API_TYPE to apiTypeMem,
                ),
            )
        val out = PointerByReference()
        val rc =
            try {
                library.mpv_render_context_create(out, ctx, params)
            } catch (e: Throwable) {
                Logger.e(TAG, "mpv_render_context_create threw: ${e.message}")
                return false
            }
        if (rc < 0) {
            Logger.e(TAG, "mpv_render_context_create failed: ${library.mpv_error_string(rc)}")
            return false
        }
        val created = out.value
        if (created == null) {
            Logger.e(TAG, "mpv_render_context_create returned a null context")
            return false
        }
        renderCtx = created

        library.mpv_render_context_set_update_callback(created, updateCallback, null)

        running = true
        renderThread =
            Thread({ renderLoop(library) }, "Mpv-Render-Thread").apply {
                isDaemon = true
                start()
            }
        Logger.d(TAG, "mpv software render context created")
        return true
    }

    /**
     * Stop rendering and free the render context.
     *
     * MUST be called before `mpv_terminate_destroy` on the owning handle.
     */
    fun detach() {
        val library = lib
        val ctx = renderCtx

        running = false
        frameSignal.release()

        val thread = renderThread
        renderThread = null
        try {
            thread?.join(1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        renderCtx = null
        if (library != null && ctx != null) {
            try {
                // Deregister first so no callback can fire against a freed context.
                library.mpv_render_context_set_update_callback(ctx, null, null)
                library.mpv_render_context_free(ctx)
            } catch (e: Throwable) {
                Logger.w(TAG, "Error freeing render context: ${e.message}")
            }
        }
        synchronized(surfaceLock) { surface = null }
        // Clear the last frame so collectors fall back to the artwork instead of holding a
        // stale image from a player that no longer exists.
        _frames.value = null
    }

    // ================= render thread =================

    private fun renderLoop(library: MpvLibrary) {
        while (running) {
            try {
                // Bounded wait so `running = false` is noticed promptly even with no frames.
                frameSignal.tryAcquire(100, TimeUnit.MILLISECONDS)
                // Collapse a backlog: if frames arrived faster than we could render, we only ever
                // want to draw the newest one, not run the loop once per missed frame.
                frameSignal.drainPermits()
                if (!running) return

                val ctx = renderCtx ?: continue

                val resized = ensureSurface()
                val target = surface ?: continue

                // Only render when mpv actually has a new frame, or when a resize invalidated the
                // buffer — SW rendering is CPU-bound, so don't run it more often than needed.
                val hasFrame = (library.mpv_render_context_update(ctx) and MpvRenderUpdateFlag.FRAME.toLong()) != 0L
                if (!hasFrame && !resized) continue

                val rc = library.mpv_render_context_render(ctx, target.renderParams)
                if (rc < 0) {
                    Logger.w(TAG, "mpv_render_context_render failed: ${library.mpv_error_string(rc)}")
                    continue
                }

                // Single bulk copy out of native memory into the staging buffer — the frame is
                // contiguous because the buffer is stride-wide. Native ints are read in platform
                // byte order, which is what makes the bgr0 <-> TYPE_INT_RGB match hold
                // (little-endian).
                val copied =
                    synchronized(surfaceLock) {
                        if (surface === target) {
                            target.pixelBuffer.read(0L, target.pixels, 0, target.pixels.size)
                            true
                        } else {
                            false
                        }
                    }
                if (!copied) continue

                // Publish a fresh snapshot with the stride padding stripped. A new image per frame
                // keeps every published frame immutable — collectors can convert it on any thread
                // with no tearing — and replaces the per-frame Java2D blit the EDT used to do, so
                // the total copy work is unchanged. StateFlow also needs a distinct instance per
                // frame: re-emitting a mutated image would be conflated away as equal.
                val snapshot = BufferedImage(target.width, target.height, BufferedImage.TYPE_INT_RGB)
                val snapshotPixels = (snapshot.raster.dataBuffer as DataBufferInt).data
                for (y in 0 until target.height) {
                    System.arraycopy(
                        target.pixels,
                        y * target.bufferWidth,
                        snapshotPixels,
                        y * target.width,
                        target.width,
                    )
                }
                _frames.value = snapshot
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (e: Throwable) {
                Logger.e(TAG, "Render loop error: ${e.message}")
            }
        }
    }

    /**
     * Allocate or reallocate the render target to match the requested size.
     *
     * Runs on the render thread so all native allocation stays off the UI.
     *
     * @return true when a new surface was allocated this call.
     */
    private fun ensureSurface(): Boolean {
        val w = requestedWidth
        val h = requestedHeight
        if (w <= 0 || h <= 0) return false

        val existing = surface
        if (existing != null && existing.width == w && existing.height == h) return false

        // Stride padded up to the alignment mpv asks for, then used as the staging row width so
        // the whole frame stays one contiguous run of pixels.
        val minStride = w.toLong() * bytesPerPixel
        val strideBytes = ((minStride + alignment - 1) / alignment) * alignment
        val bufferWidth = (strideBytes / bytesPerPixel).toInt()
        val needed = strideBytes * h

        // Over-allocate so the buffer handed to mpv can start on an aligned address.
        val backing = Memory(needed + alignment)
        val offset = (alignment - (Pointer.nativeValue(backing) % alignment)) % alignment
        val pixelBuffer = backing.share(offset, needed)

        val pixels = IntArray(bufferWidth * h)

        val sizeMem =
            Memory(8).apply {
                setInt(0, w)
                setInt(4, h)
            }
        val strideMem = Memory(8).apply { setLong(0, strideBytes) }

        val renderParams =
            buildRenderParams(
                listOf(
                    // SW_SIZE takes a pointer to int[2] ("Type: int[2] ... param.data = &s[0]").
                    MpvRenderParamType.SW_SIZE to sizeMem,
                    // SW_FORMAT takes the string pointer directly ("Type: char*").
                    MpvRenderParamType.SW_FORMAT to formatMem,
                    // SW_STRIDE takes a pointer to a size_t ("Type: size_t*").
                    MpvRenderParamType.SW_STRIDE to strideMem,
                    // SW_POINTER takes the pixel buffer pointer directly ("Type: void*").
                    MpvRenderParamType.SW_POINTER to pixelBuffer,
                ),
            )

        val next =
            Surface(
                width = w,
                height = h,
                bufferWidth = bufferWidth,
                strideBytes = strideBytes,
                pixelBuffer = pixelBuffer,
                backing = backing,
                pixels = pixels,
                sizeMem = sizeMem,
                strideMem = strideMem,
                renderParams = renderParams,
            )
        synchronized(surfaceLock) { surface = next }
        Logger.d(TAG, "Render surface allocated: ${w}x$h (stride=$strideBytes, bufferWidth=$bufferWidth)")
        return true
    }
}
