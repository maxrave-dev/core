package org.simpmusic.autoeq

import kotlin.math.abs
import kotlin.math.pow

/**
 * The ten centres AutoEq generates its fixed-band output at.
 *
 * Straight from AutoEq's own `10_BAND_GRAPHIC_EQ` config in `autoeq/constants.py`:
 * `'filters': [{'fc': 31.25 * 2 ** i} for i in range(10)]`, with `'q': math.sqrt(2)` and gains
 * bounded to `-12.0 .. 12.0`. Those three facts are why a profile drops into this app untouched —
 * the equalizer runs the same centres at Q 1.41 over the same ±12 dB.
 */
val AUTOEQ_FIXED_BANDS_HZ: List<Double> = List(10) { 31.25 * 2.0.pow(it) }

/**
 * A line of the index, e.g.
 * `- [1MORE Aero (ANC Off)](./HypetheSonics/GRAS%20RA0045%20in-ear/1MORE%20Aero%20(ANC%20Off)) by HypetheSonics on GRAS RA0045`
 *
 * The path is matched as `\S+` rather than anything cleverer: it is percent-encoded, so it is the
 * one field guaranteed to contain no spaces, which is what makes the split unambiguous even when
 * the name itself carries brackets or parentheses.
 */
private val INDEX_LINE = Regex("""^-\s+\[(.+)]\((\S+)\)\s+by\s+(.+?)(?:\s+on\s+(.+))?$""")

private val PREAMP_LINE = Regex("""^Preamp:\s*(-?[0-9.]+)\s*dB""", RegexOption.IGNORE_CASE)

private val FILTER_LINE =
    Regex(
        """^Filter\s+\d+:\s*ON\s+PK\s+Fc\s+([0-9.]+)\s*Hz\s+Gain\s+(-?[0-9.]+)\s*dB\s+Q\s+([0-9.]+)""",
        RegexOption.IGNORE_CASE,
    )

/** Every profile listed in `results/INDEX.md`. Lines that do not match the shape are skipped. */
fun parseAutoEqIndex(markdown: String): List<AutoEqIndexEntry> =
    markdown
        .lineSequence()
        .mapNotNull { line ->
            val match = INDEX_LINE.matchEntire(line.trim()) ?: return@mapNotNull null
            val name = match.groupValues[1].trim()
            val path = match.groupValues[2].removePrefix("./").trim()
            if (name.isEmpty() || path.isEmpty()) return@mapNotNull null
            AutoEqIndexEntry(
                path = path,
                name = name,
                source = match.groupValues[3].trim(),
                rig = match.groupValues[4].trim().takeIf { it.isNotEmpty() },
            )
        }.toList()

/**
 * A `<name> FixedBandEQ.txt` file, or null if it does not hold a full curve.
 *
 * Gains are placed by **frequency**, not by the filter number they were written on, so a file
 * whose filters ever arrive in another order still lands on the right bands. A file with fewer
 * than ten bands is rejected outright rather than padded: padding would apply a half correction
 * and look like it had worked.
 */
fun parseAutoEqFixedBandEq(text: String): AutoEqCurveData? {
    var preampDb = 0f
    val parsed = mutableListOf<Pair<Double, Float>>()

    text.lineSequence().forEach { raw ->
        val line = raw.trim()
        val preamp = PREAMP_LINE.find(line)
        if (preamp != null) {
            preampDb = preamp.groupValues[1].toFloatOrNull() ?: 0f
            return@forEach
        }
        val filter = FILTER_LINE.find(line) ?: return@forEach
        val hz = filter.groupValues[1].toDoubleOrNull() ?: return@forEach
        val gain = filter.groupValues[2].toFloatOrNull() ?: return@forEach
        parsed += hz to gain
    }

    if (parsed.size < AUTOEQ_FIXED_BANDS_HZ.size) return null

    // AutoEq rounds the centres on the way out — 31.25 Hz is written as "31" — so the match is a
    // tolerance rather than an equality.
    return AutoEqCurveData(
        bandsDb =
            AUTOEQ_FIXED_BANDS_HZ.map { centre ->
                parsed.firstOrNull { (hz, _) -> abs(hz - centre) <= centre * 0.05 }?.second ?: 0f
            },
        preampDb = preampDb,
    )
}
