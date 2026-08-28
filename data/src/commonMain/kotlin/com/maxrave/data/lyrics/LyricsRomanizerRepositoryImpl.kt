package com.maxrave.data.lyrics

import com.maxrave.domain.data.model.lyrics.RomanizationDictionaryState
import com.maxrave.domain.data.model.lyrics.RomanizationLanguage
import com.maxrave.domain.repository.LyricsRomanizerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.simpmusic.lyrics.romanization.LyricsRomanizer
import org.simpmusic.lyrics.romanization.RomanizationDictionaryPack
import kotlin.coroutines.cancellation.CancellationException

/**
 * The only place that knows the romanization engine exists.
 *
 * No caching layer here on purpose: ten of the twelve languages are a table lookup per character,
 * and the two that are not are already lazy inside their own platform objects. A cache keyed by
 * line would spend more memory holding a song's lyrics twice than it saves.
 *
 * This is also where the Japanese dictionary pack learns its directory: every romanize call comes
 * through this class, and it is a Koin single, so configuring the pack in the constructor is
 * guaranteed to precede the first Japanese line with no start-up hook anywhere else.
 */
class LyricsRomanizerRepositoryImpl(
    japaneseDictionaryDirectoryPath: String,
) : LyricsRomanizerRepository {
    // Declaration order is load-bearing: configure() must run before the state flow's initial
    // value asks isReady(), because on Android isReady() is a question about that very directory.
    init {
        RomanizationDictionaryPack.configure(japaneseDictionaryDirectoryPath)
    }

    private val _japaneseDictionaryState =
        MutableStateFlow(
            if (RomanizationDictionaryPack.isReady()) {
                RomanizationDictionaryState.READY
            } else {
                RomanizationDictionaryState.NOT_DOWNLOADED
            },
        )
    override val japaneseDictionaryState: StateFlow<RomanizationDictionaryState> =
        _japaneseDictionaryState.asStateFlow()

    // One download at a time: a second caller parks here until the first finishes, then sees
    // READY and leaves — never a second 13 MB fetch.
    private val downloadMutex = Mutex()

    override fun romanize(
        line: String,
        enabled: Set<RomanizationLanguage>,
    ): String? = LyricsRomanizer.romanize(line, enabled)

    override suspend fun downloadJapaneseDictionary() {
        downloadMutex.withLock {
            if (_japaneseDictionaryState.value == RomanizationDictionaryState.READY) return
            _japaneseDictionaryState.value = RomanizationDictionaryState.DOWNLOADING
            try {
                val result = RomanizationDictionaryPack.download()
                _japaneseDictionaryState.value =
                    if (result.isSuccess) {
                        RomanizationDictionaryState.READY
                    } else {
                        RomanizationDictionaryState.FAILED
                    }
            } catch (cancellation: CancellationException) {
                // The caller's scope died mid-download (settings screen closed). The service layer
                // already removed its partial files, so the truth is plain NOT_DOWNLOADED — and a
                // DOWNLOADING label left behind here would block every retry until restart.
                _japaneseDictionaryState.value = RomanizationDictionaryState.NOT_DOWNLOADED
                throw cancellation
            }
        }
    }
}
