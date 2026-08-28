package org.simpmusic.lyrics.romanization

/**
 * iOS has no Japanese analyzer at all — [PlatformRomanizer]'s ios actual answers null and the
 * line is shown as written — so there is no dictionary to be missing. Reporting ready keeps the
 * settings screen from ever offering a download that could not change anything.
 */
actual object RomanizationDictionaryPack {
    actual fun configure(directoryPath: String) {
        // No analyzer, no dictionary, nothing to configure.
    }

    actual fun isReady(): Boolean = true

    actual suspend fun download(): Result<Unit> = Result.success(Unit)
}
