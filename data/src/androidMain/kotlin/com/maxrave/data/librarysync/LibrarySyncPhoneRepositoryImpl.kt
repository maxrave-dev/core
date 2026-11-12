package com.maxrave.data.librarysync

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.maxrave.data.db.MusicDatabase
import com.maxrave.data.db.SyncDao
import com.maxrave.data.loginsync.LoginBundle
import com.maxrave.data.loginsync.LoginSyncAck
import com.maxrave.data.loginsync.LoginSyncStore
import com.maxrave.data.loginsync.loginSyncJson
import com.maxrave.domain.data.model.librarysync.LibrarySyncException
import com.maxrave.domain.data.model.librarysync.LibrarySyncProblem
import com.maxrave.domain.data.model.librarysync.LibrarySyncState
import com.maxrave.domain.data.model.librarysync.PendingSettings
import com.maxrave.domain.data.model.librarysync.SyncPeer
import com.maxrave.domain.data.model.loginsync.DesktopInfo
import com.maxrave.domain.data.model.loginsync.LoginSyncService
import com.maxrave.domain.repository.LibrarySyncPhoneRepository
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.simpmusic.loginsync.LocatedDesktop
import org.simpmusic.loginsync.PairedSyncClient
import org.simpmusic.loginsync.PairedSyncException
import org.simpmusic.loginsync.PairedTarget
import org.simpmusic.loginsync.PhoneHello
import org.simpmusic.loginsync.SyncPairInvite
import org.simpmusic.loginsync.keyFromHex

private const val TAG = "LibrarySync"
private const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

// A round is drained in batches of SYNC_BATCH; this caps one run at ~25 000 changes each way. The
// rest goes in the next run.
private const val MAX_BATCHES = 50
private const val UNPAIR_TIMEOUT_MS = 8_000L

/**
 * The phone's side of library sync: drives every round. Changes made while the computer is away wait
 * in `sync_change` and go out the next time it is found, wherever its address is by then.
 */
internal class LibrarySyncPhoneRepositoryImpl(
    private val context: Context,
    private val prefs: SyncPrefs,
    private val engine: SyncEngine,
    private val dao: SyncDao,
    private val settings: SyncedSettings,
    private val loginStore: LoginSyncStore,
    private val database: MusicDatabase,
    private val client: PairedSyncClient,
) : LibrarySyncPhoneRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // One round at a time; unpairing waits for the one in flight, so nothing lands after it.
    private val rounds = Mutex()

    private val _state = MutableStateFlow(LibrarySyncState())
    override val state: StateFlow<LibrarySyncState> = _state.asStateFlow()

    private val _pendingSettings = MutableStateFlow<PendingSettings?>(null)
    override val pendingSettings: StateFlow<PendingSettings?> = _pendingSettings.asStateFlow()

    // Between reach() and pair(): the QR and the address that answered it.
    @Volatile
    private var pendingInvite: SyncPairInvite? = null

    @Volatile
    private var pendingHost: String? = null

    init {
        scope.launch {
            prefs.state.collect { saved ->
                _state.update { state ->
                    state.copy(
                        peers = listOfNotNull(saved.desktop?.let { SyncPeer(it.desktopId, it.name, it.os, saved.lastSyncAt) }),
                        lastSyncAt = saved.lastSyncAt,
                    )
                }
                _pendingSettings.value =
                    saved.pendingSettings?.let { PendingSettings(saved.pendingSettingsFrom.orEmpty(), it.changedAt, it.values.size, it.values.keys.toList()) }
            }
        }
    }

    // ===== Pairing =====

    override fun isPairingCode(qrText: String): Boolean = SyncPairInvite.parse(qrText) != null

    override suspend fun reach(qrText: String): DesktopInfo {
        val invite = SyncPairInvite.parse(qrText) ?: throw LibrarySyncException(LibrarySyncProblem.FAILED, "not a sync pairing code")
        requireLocalNetwork()
        val (host, desktop) = transport { client.pairHello(invite, phoneHello()) }
        pendingInvite = invite
        pendingHost = host
        return DesktopInfo(desktop.name, desktop.os)
    }

    override suspend fun pair(): SyncPeer {
        val invite = pendingInvite ?: throw LibrarySyncException(LibrarySyncProblem.FAILED, "no computer reached")
        val host = checkNotNull(pendingHost)
        val grant = transport { client.pair(invite, host, phoneHello()) }
        rounds.withLock {
            prefs.update {
                it.copy(
                    desktop =
                        PairedDesktop(
                            desktopId = grant.desktopId,
                            name = grant.name,
                            os = grant.os,
                            keyHex = grant.keyHex,
                            port = grant.port,
                            preferredHost = host,
                            hosts = (listOf(host) + grant.addresses).distinct(),
                        ),
                    lastServerSeq = 0,
                    pendingSettings = null,
                    pendingSettingsFrom = null,
                )
            }
            engine.startPhoneOutbox()
        }
        cancelPairing()
        scope.launch { syncNow() }
        return SyncPeer(grant.desktopId, grant.name, grant.os)
    }

    override fun cancelPairing() {
        pendingInvite = null
        pendingHost = null
    }

    // ===== Rounds =====

    /** Never throws: what went wrong lands in [state] for the screen to explain. */
    // Runs in this repository's own scope and only waits on it here, so a caller that goes away (an
    // activity rotating, a worker stopped) does not cut a round off halfway.
    override suspend fun syncNow() = scope.launch { round() }.join()

    // A round already running is the round the caller wanted.
    private suspend fun round() {
        if (!rounds.tryLock()) return
        try {
            val desktop = prefs.current().desktop ?: return
            if (!hasLocalNetwork()) return problem(LibrarySyncProblem.NEEDS_LOCAL_NETWORK_PERMISSION)
            _state.update { it.copy(syncing = true) }
            try {
                val target = desktop.toTarget()
                val at = locate(target) ?: return
                for (batch in 0 until MAX_BATCHES) {
                    val request = engine.nextRequest()
                    val body = syncJson.encodeToString(SyncRequest.serializer(), request).encodeToByteArray()
                    val reply = syncJson.decodeFromString(SyncResponse.serializer(), client.sync(target, at, body).decodeToString())
                    val more = engine.applyReply(request, reply, desktop.name) { backupBeforeMerge(database, prefs) }
                    if (!more) break
                }
                _state.update { it.copy(problem = null, problemDetail = null) }
            } catch (e: PairedSyncException) {
                Logger.e(TAG, "round failed: ${e.kind}", e)
                when (e.kind) {
                    // locate() proved this is the paired computer; a 403 from it means it no longer holds our key.
                    // A 403 is not proof by itself: it also comes from another machine that took the
                    // address meanwhile. Only the computer's own sealed "removed" answer counts.
                    PairedSyncException.Kind.REFUSED -> {
                        if (client.locate(desktop.toTarget())?.revoked == true) {
                            forget()
                            problem(LibrarySyncProblem.REVOKED)
                        } else {
                            problem(LibrarySyncProblem.FAILED, e.detail)
                        }
                    }

                    PairedSyncException.Kind.UNREACHABLE -> {
                        problem(LibrarySyncProblem.UNREACHABLE, e.detail)
                    }

                    else -> {
                        problem(LibrarySyncProblem.FAILED, e.detail)
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.e(TAG, "round failed", e)
                problem(LibrarySyncProblem.FAILED, e.message)
            } finally {
                _state.update { it.copy(syncing = false) }
            }
        } finally {
            rounds.unlock()
        }
    }

    /** Finds the computer, remembers where it answered, and handles being told this phone was removed. */
    private suspend fun locate(target: PairedTarget): LocatedDesktop? {
        val at = client.locate(target)
        if (at == null) {
            problem(LibrarySyncProblem.UNREACHABLE)
            return null
        }
        if (at.revoked) {
            forget()
            problem(LibrarySyncProblem.REVOKED)
            return null
        }
        prefs.update { saved ->
            val desktop = saved.desktop ?: return@update saved
            saved.copy(desktop = desktop.copy(port = at.port, hosts = (listOf(at.host) + at.addresses).distinct()))
        }
        return at
    }

    // ===== Leaving =====

    override suspend fun unpairAll() {
        rounds.withLock {
            val desktop = prefs.current().desktop ?: return
            // Best effort: a computer that is away keeps this phone listed until the user removes it
            // there; its rounds are over either way, since this phone forgets the key.
            withTimeoutOrNull(UNPAIR_TIMEOUT_MS) {
                try {
                    val target = desktop.toTarget()
                    client.locate(target)?.takeIf { !it.revoked }?.let { client.unpair(target, it) }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Logger.e(TAG, "unpair: computer not told", e)
                }
            }
            forget()
            _state.update { it.copy(problem = null, problemDetail = null) }
        }
    }

    private suspend fun forget() {
        prefs.update { it.copy(desktop = null, lastServerSeq = 0, pendingSettings = null, pendingSettingsFrom = null) }
        dao.clearChanges()
    }

    // ===== Settings and sign-ins =====

    override suspend fun applyPendingSettings() = settings.applyPending()

    override suspend fun dismissPendingSettings() = settings.dismissPending()

    override suspend fun sendLogins(services: Set<LoginSyncService>): List<LoginSyncService> {
        val desktop = prefs.current().desktop ?: throw LibrarySyncException(LibrarySyncProblem.FAILED, "not paired")
        requireLocalNetwork()
        val target = desktop.toTarget()
        val at =
            transport { client.locate(target) } ?: throw LibrarySyncException(LibrarySyncProblem.UNREACHABLE, null)
        if (at.revoked) {
            forget()
            throw LibrarySyncException(LibrarySyncProblem.REVOKED, null)
        }
        val payload = loginSyncJson.encodeToString(LoginBundle.serializer(), loginStore.bundle(services)).encodeToByteArray()
        val reply = transport { client.logins(target, at, payload) }
        val ack = loginSyncJson.decodeFromString(LoginSyncAck.serializer(), reply.decodeToString())
        return ack.services.mapNotNull { name -> LoginSyncService.entries.firstOrNull { it.name == name } }
    }

    // ===== Helpers =====

    private suspend fun PairedDesktop.toTarget() =
        PairedTarget(
            desktopId = desktopId,
            phoneId = prefs.deviceId(),
            key = checkNotNull(keyHex.keyFromHex()),
            port = port,
            preferredHost = preferredHost,
            hosts = hosts,
        )

    private suspend fun phoneHello() =
        PhoneHello(
            phoneId = prefs.deviceId(),
            name = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
            os = "Android ${Build.VERSION.RELEASE}",
        )

    // Android 17 enforces it on every app that declares it, whatever the targetSdk (see AndroidManifest).
    private fun hasLocalNetwork(): Boolean =
        Build.VERSION.SDK_INT < 37 || context.checkSelfPermission(ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    private fun requireLocalNetwork() {
        if (!hasLocalNetwork()) throw LibrarySyncException(LibrarySyncProblem.NEEDS_LOCAL_NETWORK_PERMISSION, null)
    }

    private fun problem(
        problem: LibrarySyncProblem,
        detail: String? = null,
    ) = _state.update { it.copy(problem = problem, problemDetail = detail) }

    private inline fun <T> transport(block: () -> T): T =
        try {
            block()
        } catch (e: PairedSyncException) {
            val problem =
                when (e.kind) {
                    PairedSyncException.Kind.UNREACHABLE -> LibrarySyncProblem.UNREACHABLE
                    PairedSyncException.Kind.REVOKED -> LibrarySyncProblem.REVOKED
                    // An old pairing code, or a key the computer no longer holds.
                    PairedSyncException.Kind.REFUSED, PairedSyncException.Kind.FAILED -> LibrarySyncProblem.FAILED
                }
            throw LibrarySyncException(problem, e.detail)
        } catch (e: LibrarySyncException) {
            throw e
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Ktor throws plain IO exceptions for a connection that never opened.
            throw LibrarySyncException(LibrarySyncProblem.UNREACHABLE, e.message)
        }
}
