package org.simpmusic.autoeq

/** One line of AutoEq's `results/INDEX.md`. */
data class AutoEqIndexEntry(
    /** Folder path under `results/`, percent-encoded exactly as the index writes it. */
    val path: String,
    val name: String,
    val source: String,
    val rig: String?,
)

/** Gains for AutoEq's ten fixed bands, plus the preamp its own header asks for. */
data class AutoEqCurveData(
    val bandsDb: List<Float>,
    val preampDb: Float,
)

/** Outcome of asking GitHub for the index. */
sealed interface AutoEqIndexResult {
    /** The server answered 304: what is cached is still current. */
    data object NotModified : AutoEqIndexResult

    data class Updated(
        val entries: List<AutoEqIndexEntry>,
        val etag: String?,
    ) : AutoEqIndexResult

    data class Failed(
        val reason: String,
    ) : AutoEqIndexResult
}
