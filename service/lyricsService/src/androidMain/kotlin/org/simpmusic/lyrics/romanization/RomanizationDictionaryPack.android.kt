package org.simpmusic.lyrics.romanization

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Android is the one platform where the dictionary genuinely lives outside the app, so this is
 * the one actual with state: the configured directory, held here and read by [PlatformRomanizer]
 * when it decides whether it can build the analyzer. The heavy lifting — download, checksum,
 * unpacking, the kuromoji resolver — is all [KuromojiDictionary]'s.
 */
actual object RomanizationDictionaryPack {
    /** Written once at startup by the repository's constructor, read on every romanize of a Japanese line. */
    @Volatile
    internal var dictionaryDirectory: File? = null
        private set

    actual fun configure(directoryPath: String) {
        dictionaryDirectory = File(directoryPath)
    }

    actual fun isReady(): Boolean {
        val directory = dictionaryDirectory ?: return false
        return KuromojiDictionary.isReady(directory)
    }

    actual suspend fun download(): Result<Unit> {
        val directory =
            dictionaryDirectory
                ?: return Result.failure(IllegalStateException("RomanizationDictionaryPack.configure was never called"))
        return KuromojiDictionary.download(directory).onSuccess {
            // Built here, on IO, before the caller announces the pack ready. Lyrics on screen read
            // their lines again at that moment, and the first Japanese one would otherwise build
            // the analyzer on the UI thread — reading the whole dictionary mid-song. A build that
            // fails is not a failed download: the files are in place, so this stays a success and
            // the analyzer is tried again on first use, as before.
            withContext(Dispatchers.IO) { PlatformRomanizer.prepareJapanese() }
        }
    }
}
