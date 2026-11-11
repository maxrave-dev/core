package com.maxrave.domain.data.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.math.PI
import kotlin.time.TimeSource

private val eightDTimeOrigin = TimeSource.Monotonic.markNow()

/**
 * One playback-wide orbit shared by the current, incoming and precached players. There is no timer:
 * the phase is evaluated when needed. Track changes and seeks do not own or reset this clock.
 * Immutable CAS-updated snapshots allow UI/service and audio threads to meet without an audio lock.
 */
class EightDOrbit(
    private val nowNanos: () -> Long = { eightDTimeOrigin.elapsedNow().inWholeNanoseconds },
) {
    data class Snapshot(
        val effect: EightDEffect?,
        val phaseRadians: Double,
        val playing: Boolean,
        val revision: Long,
    )

    private data class State(
        val effect: EightDEffect? = null,
        val phase: Double = 0.0,
        val atNanos: Long = 0,
        val playing: Boolean = false,
        val revision: Long = 0,
    ) {
        fun phaseAt(now: Long): Double =
            if (!playing || effect == null) {
                phase
            } else {
                wrapPhase(phase + effect.radiansPerSecond * (now - atNanos).coerceAtLeast(0) / 1_000_000_000.0)
            }
    }

    private val state = MutableStateFlow(State(atNanos = nowNanos()))

    fun setEffect(value: EightDEffect?) {
        val normalized = value?.normalized()
        state.update { previous ->
            if (previous.effect == normalized) {
                previous
            } else {
                val now = nowNanos()
                previous.copy(effect = normalized, phase = previous.phaseAt(now), atNanos = now, revision = previous.revision + 1)
            }
        }
    }

    fun setPlaying(playing: Boolean) {
        state.update { previous ->
            if (previous.playing == playing) {
                previous
            } else {
                val now = nowNanos()
                previous.copy(playing = playing, phase = previous.phaseAt(now), atNanos = now, revision = previous.revision + 1)
            }
        }
    }

    fun snapshot(): Snapshot {
        val value = state.value
        return Snapshot(value.effect, value.phaseAt(nowNanos()), value.playing, value.revision)
    }

    companion object {
        fun wrapPhase(value: Double): Double {
            val cycle = 2 * PI
            val phase = value % cycle
            return if (phase < 0) phase + cycle else phase
        }
    }
}
