package org.simpmusic.lyrics.romanization

/**
 * iOS has neither library, so both answers are null and the line is shown as it was written.
 *
 * Null is the contract for "not available here", not an error path — [LyricsRomanizer] already
 * treats it as "show nothing extra". The other ten languages are pure Kotlin and DO work on iOS;
 * only these two degrade.
 *
 * The app does not currently build an iOS binary at all (the targets are commented out in
 * composeApp), so this exists to keep THIS module compiling for its own iOS targets.
 */
internal actual object PlatformRomanizer {
    actual fun japanese(line: String): String? = null

    actual fun chinese(line: String): String? = null
}
