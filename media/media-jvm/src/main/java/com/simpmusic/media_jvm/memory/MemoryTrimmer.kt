package com.simpmusic.media_jvm.memory

import com.maxrave.logger.Logger
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val TAG = "MemoryTrimmer"

/**
 * Hands memory the allocator is still holding back to the operating system.
 *
 * ## Why this exists
 * The desktop app's resident size is dominated by NATIVE memory, not by the Java heap. Measured on
 * Linux with `-Xmx512m` in force: RSS 1.9 GB while the JVM heap was using only 121 MB — the other
 * ~1.3 GB sat in the C allocator. Crucially it was NOT a leak: mpv/FFmpeg allocate and free large
 * demuxer/decoder buffers on every track, and the allocator simply keeps the freed pages instead of
 * returning them. `malloc_trim(0)` on a live process gave back 167 MB instantly (RSS 920 → 753 MB),
 * which is what proves the memory was already free.
 *
 * Every desktop allocator has this behaviour and every one of them exposes a way to ask for the
 * pages back — only the spelling differs:
 *
 * | OS      | call                                                  | since        |
 * |---------|-------------------------------------------------------|--------------|
 * | Linux   | `malloc_trim(0)`                                       | glibc        |
 * | Windows | `HeapSetInformation(NULL, HeapOptimizeResources, ...)` | Windows 8.1  |
 *
 * ## Why macOS is deliberately excluded
 * macOS has an equivalent — `malloc_zone_pressure_relief(NULL, 0)` — and it was wired up here until
 * it was traced to a startup crash. A null zone means EVERY registered zone, not just the one
 * mpv/FFmpeg allocate from, so the call also tells the zones behind Metal, QuartzCore and Skia to
 * hand their free pages back. Dispatched off a background thread it can land in the middle of a
 * `CATransaction` commit on the main thread, and the process dies with an uncaught NSException
 * raised inside `-[MTLLayer blitCallback]`.
 *
 * The race window is narrow enough that anything perturbing timing hides it — attaching
 * `log stream` was enough to make it disappear — so it only reproduced on a real Finder/Dock launch,
 * where activation and the window animation keep the main thread inside CoreAnimation for longer.
 *
 * Nothing is lost by skipping it: `MallocNanoZone=0` in the app's `LSEnvironment` already addresses
 * the allocator growth this class exists to fight on macOS.
 *
 * ## Why it must only run while idle
 * `malloc_trim` walks the heap holding the arena lock, so every thread that calls `malloc` blocks
 * until it finishes — including mpv's decoder threads. Running it during playback is audible.
 * [trim] is therefore called from the paused/idle transitions only, and [MIN_INTERVAL_MS] keeps a
 * burst of state changes from turning into a burst of heap walks.
 *
 * Failures are swallowed on purpose: a missing symbol (Windows older than 8.1, a non-glibc libc
 * such as musl) means "this platform cannot give the pages back", which is a normal outcome, not an
 * error worth surfacing to the user.
 */
object MemoryTrimmer {
    /**
     * Floor between two heap walks. Playback state flips far more often than this — pause/resume,
     * track changes, buffering — and trimming on each one would cost more than it reclaims.
     */
    private const val MIN_INTERVAL_MS = 60_000L

    /** `HEAP_INFORMATION_CLASS.HeapOptimizeResources`, winnt.h. */
    private const val HEAP_OPTIMIZE_RESOURCES = 3

    /** `HEAP_OPTIMIZE_RESOURCES_CURRENT_VERSION`, winnt.h. */
    private const val HEAP_OPTIMIZE_RESOURCES_CURRENT_VERSION = 1

    private val lastTrimAt = AtomicLong(0)

    /** Set once the platform has told us it has no way to do this, so we stop asking. */
    private val unsupported = AtomicBoolean(false)

    // ================= native bindings =================

    // `size_t` is mapped to Kotlin `Long` rather than JNA's NativeLong throughout this file.
    // NativeLong follows C `long`, which is 8 bytes on the LP64 Unixes but only 4 on Windows'
    // LLP64 — so it would silently pass a half-width SIZE_T to HeapSetInformation. Every target
    // this app ships to is 64-bit, where size_t is 8 bytes on all three platforms, which is
    // exactly what a Kotlin Long marshals to.

    /** glibc: `int malloc_trim(size_t pad)` — malloc.h. Returns 1 if any memory was released. */
    private interface GlibC : Library {
        fun malloc_trim(pad: Long): Int
    }

    /**
     * Windows: `BOOL HeapSetInformation(HANDLE, HEAP_INFORMATION_CLASS, PVOID, SIZE_T)` — heapapi.h.
     *
     * Microsoft documents the null-handle form specifically: *"If HeapSetInformation is called with
     * HeapHandle set to NULL, then all heaps in the process with a low-fragmentation heap (LFH) will
     * have their caches optimized, and the memory will be decommitted if possible."* That decommit
     * is the Windows equivalent of what `malloc_trim` does on Linux.
     */
    private interface Kernel32 : Library {
        fun HeapSetInformation(
            heapHandle: Pointer?,
            informationClass: Int,
            information: Structure?,
            informationLength: Long,
        ): Boolean
    }

    /** `HEAP_OPTIMIZE_RESOURCES_INFORMATION` — winnt.h. Both fields are DWORD. */
    @Structure.FieldOrder("Version", "Flags")
    class HeapOptimizeResourcesInformation : Structure() {
        @JvmField
        var Version: Int = HEAP_OPTIMIZE_RESOURCES_CURRENT_VERSION

        @JvmField
        var Flags: Int = 0
    }

    private val glibc: GlibC? by lazy {
        runCatching { Native.load("c", GlibC::class.java) }
            .onFailure { Logger.d(TAG, "glibc malloc_trim unavailable: ${it.message}") }
            .getOrNull()
    }

    private val kernel32: Kernel32? by lazy {
        runCatching { Native.load("kernel32", Kernel32::class.java) }
            .onFailure { Logger.d(TAG, "kernel32 HeapSetInformation unavailable: ${it.message}") }
            .getOrNull()
    }

    // ================= public API =================

    /**
     * Ask the allocator to return free pages to the OS, at most once per [MIN_INTERVAL_MS].
     *
     * MUST only be called when playback is paused, stopped, or a player handle was just released —
     * see the threading note in the class docs.
     *
     * @param reason short label for the log line, so a growing RSS can be correlated with which
     *   transitions actually triggered a trim.
     */
    fun trim(reason: String) {
        if (unsupported.get()) return

        val now = System.currentTimeMillis()
        val previous = lastTrimAt.get()
        if (previous != 0L && now - previous < MIN_INTERVAL_MS) return
        // Only one thread may proceed; a lost CAS means someone else is already trimming.
        if (!lastTrimAt.compareAndSet(previous, now)) return

        val started = System.nanoTime()
        val released =
            try {
                trimNative()
            } catch (e: Throwable) {
                // UnsatisfiedLinkError lands here when the symbol is missing entirely.
                Logger.d(TAG, "Trim unavailable on this platform: ${e.message}")
                unsupported.set(true)
                return
            }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000.0
        Logger.d(TAG, "Trim ($reason): $released, took %.1f ms".format(elapsedMs))
    }

    /** Reset the throttle so the next [trim] runs immediately. */
    fun resetThrottle() {
        lastTrimAt.set(0)
    }

    private fun trimNative(): String {
        return when {
            com.sun.jna.Platform.isWindows() -> {
                val lib = kernel32 ?: return "kernel32 unavailable"
                val info = HeapOptimizeResourcesInformation()
                info.write()
                val ok =
                    lib.HeapSetInformation(
                        null,
                        HEAP_OPTIMIZE_RESOURCES,
                        info,
                        info.size().toLong(),
                    )
                // Windows 8 and older reject the information class outright; that is a "no" answer,
                // not a crash, so we simply stop asking.
                if (!ok) unsupported.set(true)
                if (ok) "heap caches decommitted" else "not supported (needs Windows 8.1+)"
            }

            com.sun.jna.Platform.isMac() -> {
                // Never trim on macOS — the null-zone call reaches Metal's and QuartzCore's zones
                // too and can crash a CATransaction commit. See the class docs for the full trace.
                // Latching `unsupported` stops every later transition from re-entering this branch.
                unsupported.set(true)
                "disabled on macOS"
            }

            else -> {
                val lib = glibc ?: return "glibc unavailable"
                // Returns 1 when it managed to hand pages back, 0 when there was nothing to release.
                if (lib.malloc_trim(0L) == 1) "pages returned" else "nothing to release"
            }
        }
    }
}
