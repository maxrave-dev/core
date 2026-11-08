package com.simpmusic.media_jvm.mpv

import com.maxrave.domain.data.player.DelayTaps

// The `lavfi` graph bodies behind the two audio effects, deliberately kept out of MpvPlayer.
//
// These are pure functions over the same primitives Android's processors read, so the exact string
// a given setting produces can be pasted straight into `ffmpeg -af "<graph>"` and checked — no
// libmpv handle, no running track, no guessing at what mpv received. MpvPlayer only wraps the
// result in `@label:lavfi=[ ]` and hands it to the `af` property.

/** `af_aecho.c:139` — *"delay[%d]: %f is out of allowed range: (0, 90000]"*. */
private const val AECHO_MAX_DELAY_MS = 90_000

/**
 * Render an echo coefficient so it parses back to the SAME `Float` mpv was handed.
 *
 * Deliberately not [mpvNumber], which is the right formatter everywhere else here: its four
 * decimals are plenty for a filter cutoff or a mix weight, but the echo has to land within 1 LSB of
 * `EchoAudioProcessor`, whose kernel multiplies these exact `Float` values — and rounding to four
 * decimals spends the entire budget before a sample is touched. `Float.toString()` is
 * locale-independent and emits the shortest decimal that reads back identically, including exponent
 * form for a quiet tail — which both readers accept: `av_sscanf("%f")` for the decay list
 * (`af_aecho.c` `fill_items`) and `av_strtod` for the two gains, the latter only because C `strtod`
 * consumes the exponent before av_strtod looks for an SI postfix (where `E` would mean exa).
 */
private fun aechoNumber(value: Float): String = value.toString()

/**
 * The `aecho` entry for [taps], or null when the taps are outside what the filter accepts.
 *
 * Returning null rather than an unsatisfiable string is the point of this function. `af` is ONE
 * property carrying the equalizer as well, so a rejected echo does not fail on its own — it takes
 * the whole chain down with it, and the user hears the equalizer stop working for no visible
 * reason. `af_aecho.c:138` wants every delay in (0, 90000] ms and every decay strictly inside
 * (0, 1].
 *
 * The ranges are checked against the RENDERED numbers, not the raw floats, so that whatever the
 * formatter does to a value is what gets judged — [aechoNumber] round-trips, but that is a property
 * worth verifying here rather than assuming one function away.
 */
internal fun aechoGraph(taps: DelayTaps): String? {
    if (taps.delaysMs.isEmpty() || taps.delaysMs.size != taps.decays.size) return null
    if (taps.delaysMs.any { it <= 0 || it > AECHO_MAX_DELAY_MS }) return null
    val decays = taps.decays.map { aechoNumber(it) }
    if (decays.any { it.toFloat() <= 0f || it.toFloat() > 1f }) return null
    val inGain = aechoNumber(taps.inGain)
    val outGain = aechoNumber(taps.outGain)
    if (inGain.toFloat() !in 0f..1f || outGain.toFloat() !in 0f..1f) return null
    // aecho=in_gain:out_gain:<d1|d2|…>:<g1|g2|…>, delays in whole milliseconds.
    return "aecho=$inGain:$outGain:${taps.delaysMs.joinToString("|")}:${decays.joinToString("|")}"
}

/**
 * The convolution-reverb entry: the dry signal and a wet copy convolved with the impulse response
 * at [irPath], mixed at [mix].
 *
 * `afir`'s own `dry`/`wet` are input and output GAINS, not a blend, so the wet/dry balance has to
 * be built by hand — hence `asplit` … `amix`. Both are pinned to 1 and `irnorm=-1` disables
 * `afir`'s IR normalisation, because the generator already normalises to unit energy and letting
 * the filter re-normalise would make the loudness depend on which preset is loaded.
 *
 * The graph has exactly one unconnected input pad and one output pad — `amovie` is a source, not
 * an input — which is what mpv's `f_lavfi.c` requires of an `af=lavfi=[…]` graph.
 */
internal fun convolutionReverbGraph(
    irPath: String,
    mix: Float,
): String =
    "asplit[dry][wet];" +
        "amovie=${lavfiEscapePath(irPath)}[ir];" +
        "[wet][ir]afir=dry=1:wet=1:irnorm=-1[w];" +
        "[dry][w]amix=inputs=2:weights=${reverbWeights(mix)}:normalize=0"

/**
 * `amix`'s `weights` value for a wet/dry blend of [mix], as `"<dry> <wet>"`.
 *
 * Shared by [convolutionReverbGraph] and [MpvPlayer.setReverbMix] on purpose. A preset change
 * rebuilds the graph while dragging the mix slider retunes the live one through `af-command`, and
 * if those two paths ever computed the balance differently the sound would jump the moment a
 * rebuild happened to win.
 */
internal fun reverbWeights(mix: Float): String {
    val wet = mix.coerceIn(0f, 1f)
    return "${mpvNumber(1f - wet)} ${mpvNumber(wet)}"
}

/**
 * Escape a filesystem path so it survives intact as `amovie`'s filename.
 *
 * There are TWO parsers between this string and `avformat_open_input`, and they consume different
 * characters, which is why the backslash counts below are not uniform:
 *
 *  1. **The filtergraph parser** (`avfilter_graph_parse2`) reads a filter's argument list with
 *     `av_get_token`, stopping at `,` `;` `[` `]`, treating `'` as a quote and removing one layer
 *     of backslashes from everything it passes on.
 *  2. **The filter's own option parser** (`av_opt_set_from_string`) then splits what is left on
 *     `:`, reads `key=value` pairs on `=`, honours `'` again — and removes another layer of
 *     backslashes.
 *
 * So a character the first parser eats needs one backslash, a character only the second one eats
 * needs two (one is consumed keeping the backslash alive through layer 1), and `'` — which both
 * parsers treat as a quote — needs three. Measured against ffmpeg 9.0.1 rather than reasoned from
 * the docs: with one backslash a path containing `:` opens as its own prefix, with no error until
 * `avformat_open_input` reports a file nobody asked for. Spaces need nothing at either layer.
 *
 * mpv adds a third layer above these and it does NOT need feeding: v0.41.0 `m_option.c`'s
 * `read_subparam` splices the text between `[` and `]` out verbatim, touching no backslashes at
 * all (it quotes with brackets and `%n%` instead). The same function counts bracket BALANCE rather
 * than stopping at the first `]`, which is what lets [convolutionReverbGraph] carry its `[dry]`
 * `[wet]` `[ir]` pad labels inside an `af` entry in the first place. The one thing it cannot
 * survive is an unpaired bracket in the path itself, since a `]` there is counted like any other —
 * worth stating rather than working around, because nothing this app builds a path from has one.
 *
 * Backslashes are turned into forward slashes first, so Windows' `C:\Users\…` arrives as
 * `C:/Users/…` (libavfilter would otherwise read each separator as an escape). That also means a
 * POSIX filename containing a literal backslash is not representable here — no path this app
 * generates has one, and the alternative is a separator rule that differs per platform.
 */
internal fun lavfiEscapePath(path: String): String =
    buildString(path.length + 16) {
        for (ch in path.replace('\\', '/')) {
            when (ch) {
                // Quote at both layers.
                '\'' -> append("\\\\\\")
                // Option separators, consumed by the filter's own parser only.
                ':', '=' -> append("\\\\")
                // Filtergraph separators.
                '[', ']', ',', ';' -> append('\\')
            }
            append(ch)
        }
    }
