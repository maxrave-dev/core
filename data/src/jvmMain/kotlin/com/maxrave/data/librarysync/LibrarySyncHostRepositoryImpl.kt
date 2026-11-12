package com.maxrave.data.librarysync

import com.maxrave.data.db.MusicDatabase
import com.maxrave.data.db.SyncDao
import com.maxrave.data.loginsync.LoginBundle
import com.maxrave.data.loginsync.LoginSyncAck
import com.maxrave.data.loginsync.LoginSyncStore
import com.maxrave.data.loginsync.loginSyncJson
import com.maxrave.data.loginsync.services
import com.maxrave.domain.data.model.librarysync.LibrarySyncPairingState
import com.maxrave.domain.data.model.librarysync.LibrarySyncProblem
import com.maxrave.domain.data.model.librarysync.LibrarySyncState
import com.maxrave.domain.data.model.librarysync.PendingSettings
import com.maxrave.domain.data.model.librarysync.ReceivedLogins
import com.maxrave.domain.data.model.librarysync.SyncPeer
import com.maxrave.domain.data.model.loginsync.LoginSyncService
import com.maxrave.domain.data.model.loginsync.NetAddress
import com.maxrave.domain.repository.LibrarySyncHostRepository
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.simpmusic.loginsync.PairedSyncServer
import org.simpmusic.loginsync.PairedSyncWire
import org.simpmusic.loginsync.PhoneHello
import org.simpmusic.loginsync.SyncPairInvite
import org.simpmusic.loginsync.keyFromHex
import org.simpmusic.loginsync.newPairingKey
import org.simpmusic.loginsync.reachableAddresses
import org.simpmusic.loginsync.toKeyHex
import java.net.BindException
import java.util.concurrent.TimeUnit

private const val TAG = "LibrarySync"
private const val PAIRING_OPEN_MS = 5L * 60 * 1000
private const val DAY_MS = 24L * 60 * 60 * 1000
private const val LOGIN_ANSWER_MS = 100_000L
private const val REVOKED_KEPT_MS = 30 * DAY_MS

/**
 * The computer's side of library sync: holds the log, merges what each paired phone sends, and keeps
 * the paired server open while a phone is paired (or the pairing QR is shown, or a removed phone has
 * yet to be told). Rounds from different phones are merged one at a time.
 */
internal class LibrarySyncHostRepositoryImpl(
    private val prefs: SyncPrefs,
    private val engine: SyncEngine,
    private val dao: SyncDao,
    private val settings: SyncedSettings,
    private val loginStore: LoginSyncStore,
    private val database: MusicDatabase,
) : LibrarySyncHostRepository,
    PairedSyncServer.Handler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server = PairedSyncServer(this)
    private val lifecycle = Mutex()
    private val merging = Mutex()

    private val _state = MutableStateFlow(LibrarySyncState())
    override val state: StateFlow<LibrarySyncState> = _state.asStateFlow()

    private val _pairing = MutableStateFlow<LibrarySyncPairingState>(LibrarySyncPairingState.Idle)
    override val pairing: StateFlow<LibrarySyncPairingState> = _pairing.asStateFlow()

    private val _pendingSettings = MutableStateFlow<PendingSettings?>(null)
    override val pendingSettings: StateFlow<PendingSettings?> = _pendingSettings.asStateFlow()

    private val _pendingLogins = MutableStateFlow<ReceivedLogins?>(null)
    override val pendingLogins: StateFlow<ReceivedLogins?> = _pendingLogins.asStateFlow()

    // One sign-in prompt at a time; the answer the user gives on this computer.
    private val loginPrompt = Mutex()

    @Volatile
    private var loginAnswer: CompletableDeferred<Boolean>? = null

    @Volatile
    private var deviceId = ""

    @Volatile
    private var pairingKey: ByteArray? = null
    private var pairingTimeout: Job? = null
    private var addresses = emptyList<NetAddress>()
    private var selected: NetAddress? = null

    init {
        scope.launch {
            prefs.state.collect { saved ->
                _state.update { state ->
                    state.copy(peers = saved.phones.map { SyncPeer(it.phoneId, it.name, it.os, it.lastSyncAt) }, lastSyncAt = saved.lastSyncAt)
                }
                _pendingSettings.value =
                    saved.pendingSettings?.let { PendingSettings(saved.pendingSettingsFrom.orEmpty(), it.changedAt, it.values.size, it.values.keys.toList()) }
            }
        }
    }

    override suspend fun start() {
        lifecycle.withLock {
            deviceId = prefs.deviceId()
            val now = nowMillis()
            val saved = prefs.update { it.copy(revoked = it.revoked.filter { phone -> now - phone.since < REVOKED_KEPT_MS }) }
            if (saved.phones.isNotEmpty() || saved.revoked.isNotEmpty()) openServer()
        }
    }

    // ===== Pairing =====

    override suspend fun startPairing() {
        lifecycle.withLock {
            deviceId = prefs.deviceId()
            _pairing.value = LibrarySyncPairingState.Starting
            val found = withContext(Dispatchers.IO) { reachableAddresses() }.map { NetAddress(it.interfaceName, it.ip) }
            if (found.isEmpty()) {
                _pairing.value = LibrarySyncPairingState.NoNetwork
                return@withLock
            }
            addresses = found
            selected = selected?.takeIf { it in found } ?: found.first()
            pairingKey = newPairingKey()
            if (!openServer()) {
                pairingKey = null
                _pairing.value = LibrarySyncPairingState.Failed(LibrarySyncProblem.PORT_IN_USE, _state.value.problemDetail)
                return@withLock
            }
            _pairing.value = waiting()
            pairingTimeout?.cancel()
            pairingTimeout =
                scope.launch {
                    delay(PAIRING_OPEN_MS)
                    stopPairing()
                }
        }
    }

    override fun selectPairingAddress(address: NetAddress) {
        if (_pairing.value !is LibrarySyncPairingState.Waiting || address !in addresses) return
        selected = address
        _pairing.value = waiting()
    }

    override suspend fun stopPairing() {
        lifecycle.withLock {
            pairingKey = null
            pairingTimeout?.cancel()
            _pairing.value = LibrarySyncPairingState.Idle
            closeServerIfIdle()
        }
    }

    private fun waiting(): LibrarySyncPairingState.Waiting {
        val address = checkNotNull(selected)
        val invite = SyncPairInvite(listOf(address.ip), PairedSyncWire.PORT, checkNotNull(pairingKey))
        return LibrarySyncPairingState.Waiting(invite.toUri(), addresses, address)
    }

    // ===== Phones =====

    // Both wait for a merge in flight, so nothing a removed phone sent lands after it is removed.
    override suspend fun removePhone(phoneId: String) {
        merging.withLock {
            prefs.update { saved ->
                val phone = saved.phones.firstOrNull { it.phoneId == phoneId } ?: return@update saved
                saved.copy(phones = saved.phones - phone, revoked = saved.revoked + phone.copy(since = nowMillis()))
            }
            if (prefs.current().phones.isEmpty()) dao.clearChanges()
        }
        lifecycle.withLock { closeServerIfIdle() }
    }

    override suspend fun unpairAll() {
        merging.withLock {
            prefs.update { saved ->
                saved.copy(
                    phones = emptyList(),
                    revoked = saved.revoked + saved.phones.map { it.copy(since = nowMillis()) },
                    pendingSettings = null,
                    pendingSettingsFrom = null,
                )
            }
            dao.clearChanges()
        }
        lifecycle.withLock { closeServerIfIdle() }
    }

    override suspend fun syncNow() = Unit

    override suspend fun applyPendingSettings() = settings.applyPending()

    override suspend fun dismissPendingSettings() = settings.dismissPending()

    override suspend fun checkNetworkProfile() {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return
        val categories =
            withContext(Dispatchers.IO) {
                runCatching {
                    val process =
                        ProcessBuilder(
                            "powershell",
                            "-NoProfile",
                            "-NonInteractive",
                            "-Command",
                            "(Get-NetConnectionProfile).NetworkCategory",
                        ).redirectErrorStream(true).start()
                    val out = process.inputStream.bufferedReader().use { it.readText() }
                    process.waitFor(10, TimeUnit.SECONDS)
                    out.lines().map { it.trim() }.filter { it.isNotEmpty() }
                }.getOrDefault(emptyList())
            }
        val public = categories.any { it.equals("Public", ignoreCase = true) }
        _state.update { state ->
            when {
                public -> state.copy(problem = LibrarySyncProblem.WINDOWS_PUBLIC_NETWORK, problemDetail = null)
                state.problem == LibrarySyncProblem.WINDOWS_PUBLIC_NETWORK -> state.copy(problem = null)
                else -> state
            }
        }
    }

    // ===== Server lifecycle (callers hold [lifecycle]) =====

    private suspend fun openServer(): Boolean =
        try {
            server.start()
            _state.update { if (it.problem == LibrarySyncProblem.PORT_IN_USE) it.copy(problem = null, problemDetail = null) else it }
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.e(TAG, "server did not start", e)
            val problem = if (e is BindException) LibrarySyncProblem.PORT_IN_USE else LibrarySyncProblem.FAILED
            _state.update { it.copy(problem = problem, problemDetail = e.message) }
            false
        }

    private suspend fun closeServerIfIdle() {
        val saved = prefs.current()
        if (saved.phones.isEmpty() && saved.revoked.isEmpty() && pairingKey == null) server.stop()
    }

    // ===== PairedSyncServer.Handler =====

    override fun desktopId(): String = deviceId

    override fun pairingKey(): ByteArray? = pairingKey

    override suspend fun onPair(phone: PhoneHello): ByteArray {
        val key = newPairingKey()
        // The first phone starts the log from this computer's library as it stands; while phones were
        // paired, every change was logged as it happened.
        if (prefs.current().phones.isEmpty()) engine.startHostLog()
        prefs.update { saved ->
            saved.copy(
                phones = saved.phones.filterNot { it.phoneId == phone.phoneId } + PairedPhone(phone.phoneId, phone.name, phone.os, key.toKeyHex(), nowMillis()),
                revoked = saved.revoked.filterNot { it.phoneId == phone.phoneId },
            )
        }
        pairingKey = null
        pairingTimeout?.cancel()
        _pairing.value = LibrarySyncPairingState.Paired(SyncPeer(phone.phoneId, phone.name, phone.os))
        Logger.i(TAG, "paired with ${phone.os}")
        return key
    }

    override fun keyFor(phoneId: String): ByteArray? =
        prefs.state.value.phones
            .firstOrNull { it.phoneId == phoneId }
            ?.keyHex
            ?.keyFromHex()

    override fun revokedKeyFor(phoneId: String): ByteArray? =
        prefs.state.value.revoked
            .firstOrNull { it.phoneId == phoneId }
            ?.keyHex
            ?.keyFromHex()

    // Kept until its 30 days run out, not dropped once told: the reply can be lost, and a phone that
    // never hears it would knock as "unreachable" forever.
    override suspend fun onRevokedTold(phoneId: String) = Unit

    override suspend fun onSync(
        phoneId: String,
        request: ByteArray,
    ): ByteArray =
        merging.withLock {
            val phone = prefs.current().phones.first { it.phoneId == phoneId }
            val parsed = syncJson.decodeFromString(SyncRequest.serializer(), request.decodeToString())
            _state.update { it.copy(syncing = true) }
            try {
                // The year before this phone was paired, in the database's own timestamp encoding.
                val historyFrom = (phone.since - HISTORY_WINDOW_DAYS * DAY_MS).millisToWall().toStoredMillis()
                val reply = engine.handle(parsed, phone.name, historyFrom) { backupBeforeMerge(database, prefs) }
                val now = nowMillis()
                prefs.update { saved ->
                    saved.copy(lastSyncAt = now, phones = saved.phones.map { if (it.phoneId == phoneId) it.copy(lastSyncAt = now) else it })
                }
                syncJson.encodeToString(SyncResponse.serializer(), reply).encodeToByteArray()
            } finally {
                _state.update { it.copy(syncing = false) }
            }
        }

    override suspend fun onUnpair(phoneId: String) {
        prefs.update { saved -> saved.copy(phones = saved.phones.filterNot { it.phoneId == phoneId }) }
        if (prefs.current().phones.isEmpty()) dao.clearChanges()
        scope.launch { lifecycle.withLock { closeServerIfIdle() } }
    }

    override suspend fun onLogins(
        phoneId: String,
        payload: ByteArray,
    ): ByteArray {
        val phone = prefs.current().phones.first { it.phoneId == phoneId }
        val bundle = loginSyncJson.decodeFromString(LoginBundle.serializer(), payload.decodeToString())
        val offered = bundle.services()
        // Nothing is signed in until the user here says so: holding the paired key is not enough, a lost
        // phone must not be able to switch this computer's accounts. A second phone sending while one
        // prompt is open is turned down rather than queued behind it.
        val taken: List<LoginSyncService> =
            if (offered.isEmpty() || !loginPrompt.tryLock()) {
                emptyList()
            } else {
                try {
                    val answer = CompletableDeferred<Boolean>()
                    loginAnswer = answer
                    _pendingLogins.value = ReceivedLogins(phone.name, offered)
                    // Answered before the phone stops waiting (its request gives up after 120 s).
                    if (withTimeoutOrNull(LOGIN_ANSWER_MS) { answer.await() } == true) loginStore.restore(bundle) else emptyList()
                } finally {
                    loginAnswer = null
                    _pendingLogins.value = null
                    loginPrompt.unlock()
                }
            }
        return loginSyncJson.encodeToString(LoginSyncAck.serializer(), LoginSyncAck(taken.map { it.name })).encodeToByteArray()
    }

    override fun answerLogins(accept: Boolean) {
        loginAnswer?.complete(accept)
    }
}
