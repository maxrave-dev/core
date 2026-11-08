package com.maxrave.data.lyrics

import com.maxrave.domain.data.model.lyrics.RomanizationDictionaryState
import com.maxrave.domain.data.model.lyrics.RomanizationLanguage
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.LyricsRomanizerRepository
import com.maxrave.logger.Logger
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.launch
import org.simpmusic.lyrics.romanization.LyricsRomanizer
import org.simpmusic.lyrics.romanization.RomanizationDictionaryPack
import kotlin.concurrent.Volatile
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private const val TAG = "LyricsRomanizer"

/**
 * After a failed download, automatic requests do nothing for this long, doubling with every
 * automatic failure in a row up to [MAX_AUTOMATIC_RETRY_DELAY]; the first one after that retries.
 * Lyrics are opened over and over, and there is no resume: on a connection that keeps dropping long
 * transfers, every attempt starts the 13 MB again from byte 0. The user's own retry in Settings is
 * never held back, and when a download it started fails the schedule starts over from the first
 * delay. One it merely joined was started automatically, and fails as one.
 */
private val FIRST_AUTOMATIC_RETRY_DELAY = 2.minutes
private val MAX_AUTOMATIC_RETRY_DELAY = 1.hours

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
 *
 * And it puts the pack back when it goes missing. The two do not live together: the selection is
 * in the settings file, so it travels with every backup, while the pack is a download kept only in
 * this install's files directory. Reinstalling and restoring a backup therefore brings Japanese
 * back with nothing on disk.
 */
class LyricsRomanizerRepositoryImpl(
    japaneseDictionaryDirectoryPath: String,
    dataStoreManager: DataStoreManager,
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

    // How far the automatic schedule has backed off: failures in a row, where a failed download
    // started from Settings counts as the first. Only download jobs touch it, and they never
    // overlap: the next one starts only after a claim has read the state the previous one
    // published after writing it.
    private var consecutiveFailures = 0

    // Until when automatic requests do nothing. Written before FAILED is published, so whoever
    // reads FAILED also sees the window that failure opened.
    @Volatile
    private var automaticRetryAt: TimeMark? = null

    // Downloads run here, never in the caller's scope. The caller is a screen, and the settings
    // screen used to own the download: leaving it mid-way cancelled the fetch and left Japanese
    // selected with no dictionary behind it. Nothing launched here may take the process down
    // either — a failure is logged, and Japanese lines are shown as written.
    private val scope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                CoroutineExceptionHandler { _, failure ->
                    Logger.e(TAG, "Japanese dictionary: unexpected failure", failure)
                },
        )

    // Last in the class on purpose: it launches straight away, so everything above must exist.
    init {
        // The settings dialog used to be the only thing that ever started a download, so after a
        // reinstall with a restored backup the lyrics silently lost their Japanese reading and the
        // settings row went on listing Japanese as if it worked. The lyrics screen now asks for
        // the pack itself; this covers everything else that reaches the romanizer first, Settings
        // above all. Skipped when the pack is already there, downloaded or bundled.
        if (_japaneseDictionaryState.value != RomanizationDictionaryState.READY) {
            scope.launch {
                val selected = RomanizationLanguage.parse(dataStoreManager.romanizationLanguages.first())
                if (RomanizationLanguage.JAPANESE in selected) ensureJapaneseDictionary()
            }
        }
    }

    override fun romanize(
        line: String,
        enabled: Set<RomanizationLanguage>,
    ): String? = LyricsRomanizer.romanize(line, enabled)

    override fun ensureJapaneseDictionary() {
        // The state first, then the window — the reverse of the order the download job writes
        // them in — so a FAILED seen here always comes with the window that failure opened.
        if (_japaneseDictionaryState.value == RomanizationDictionaryState.FAILED &&
            automaticRetryAt?.hasNotPassedNow() == true
        ) {
            return
        }
        startJapaneseDownload(automatic = true)
    }

    override suspend fun downloadJapaneseDictionary(): RomanizationDictionaryState {
        startJapaneseDownload(automatic = false)
        // Waits for whichever download is running: this caller's, or one already in flight. A
        // caller that goes away only stops waiting; the download itself carries on.
        return _japaneseDictionaryState.first { it != RomanizationDictionaryState.DOWNLOADING }
    }

    /** Starts a download in [scope] unless the pack is READY or one is already running. */
    private fun startJapaneseDownload(automatic: Boolean) {
        // Claiming DOWNLOADING in one atomic step is what keeps this to a single 13 MB fetch: the
        // lyrics screen, the check above and a confirm in Settings can all race here, and only one
        // wins the swap.
        val previous =
            _japaneseDictionaryState.getAndUpdate { state ->
                if (state.needsDownload()) RomanizationDictionaryState.DOWNLOADING else state
            }
        if (!previous.needsDownload()) return
        // Nothing may stand between the claim above and this launch: anything that threw in
        // between would leave DOWNLOADING with no job behind it, for every later attempt to wait on.
        scope.launch {
            var outcome = RomanizationDictionaryState.FAILED
            try {
                // Inside the try for the same reason: the finally below must cover everything.
                Logger.i(TAG, "Downloading the Japanese dictionary")
                // Always ends: the pack bounds its own fetch, the one part a slow connection can
                // stretch out (KuromojiDictionary.FETCH_TIMEOUT).
                if (RomanizationDictionaryPack.download().isSuccess) outcome = RomanizationDictionaryState.READY
            } finally {
                // Published even when download() throws or is cancelled: a DOWNLOADING left behind
                // would make every later attempt take it for a download in progress and give up.
                var retryDelay: Duration? = null
                if (outcome == RomanizationDictionaryState.FAILED) {
                    consecutiveFailures = if (automatic) consecutiveFailures + 1 else 1
                    retryDelay = automaticRetryDelay(consecutiveFailures)
                    automaticRetryAt = TimeSource.Monotonic.markNow() + retryDelay
                } else {
                    consecutiveFailures = 0
                }
                _japaneseDictionaryState.value = outcome
                // After the state, so a logger that throws cannot keep it from being published.
                // Every failure lands here, including the one the layers underneath stay silent
                // about: a cancellation that download() rethrew without a word.
                if (retryDelay != null) {
                    Logger.w(TAG, "Japanese dictionary not installed; automatic retries resume in $retryDelay")
                }
            }
        }
    }

    private fun RomanizationDictionaryState.needsDownload(): Boolean =
        this == RomanizationDictionaryState.NOT_DOWNLOADED || this == RomanizationDictionaryState.FAILED

    /** 2, 4, 8, 16, 32 minutes, then an hour from the sixth automatic failure in a row on. */
    private fun automaticRetryDelay(failures: Int): Duration =
        (FIRST_AUTOMATIC_RETRY_DELAY * (1 shl (failures - 1).coerceIn(0, 5))).coerceAtMost(MAX_AUTOMATIC_RETRY_DELAY)
}
