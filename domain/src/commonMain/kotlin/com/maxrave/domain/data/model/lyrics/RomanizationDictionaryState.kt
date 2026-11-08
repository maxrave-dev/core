package com.maxrave.domain.data.model.lyrics

/**
 * Where the Japanese romanization dictionary stands on this device.
 *
 * Japanese is the one language whose romanizer needs a ~13 MB morphological dictionary, which is
 * no longer packaged inside the Android APK — it is fetched on demand: when the user turns Japanese
 * on, and again whenever Japanese is found selected with the pack missing (a reinstall that restored
 * a backup brings the selection back, not the pack). The other eleven languages never leave [READY]
 * territory conceptually and do not consult this state at all; platforms that still bundle the
 * dictionary (Desktop) report [READY] from the start.
 */
enum class RomanizationDictionaryState {
    /** No dictionary on disk yet — Japanese lines are left as they are, the existing null contract. */
    NOT_DOWNLOADED,

    /** A download is running right now — on Android, until the analyzer is built from it too. */
    DOWNLOADING,

    /** All dictionary files are present; the romanizer can build its analyzer. */
    READY,

    /**
     * The last download attempt failed. Selecting Japanese again in Settings retries at once;
     * opening lyrics, or the next song's lyrics, retries once 2 minutes have passed since the
     * failure — longer after several in a row. Nothing retries on a timer.
     */
    FAILED,
}
