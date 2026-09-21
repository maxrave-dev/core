package com.maxrave.data.loginsync

import com.maxrave.domain.data.model.loginsync.LoginSyncHostState
import com.maxrave.domain.data.model.loginsync.LoginSyncService
import com.maxrave.domain.data.model.loginsync.NetAddress
import com.maxrave.domain.repository.LoginSyncHostRepository
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.simpmusic.loginsync.LoginSyncServer
import org.simpmusic.loginsync.reachableAddresses

private const val TAG = "LoginSyncHost"

/** Desktop: runs the loginSync service's server and restores whatever sign-ins the phone sends. */
internal class LoginSyncHostRepositoryImpl(
    private val store: LoginSyncStore,
) : LoginSyncHostRepository,
    LoginSyncServer.Handler {
    private val _state = MutableStateFlow<LoginSyncHostState>(LoginSyncHostState.Starting)
    override val state: StateFlow<LoginSyncHostState> = _state.asStateFlow()

    private val server = LoginSyncServer(this)

    // Owns stop(): it must finish even when the caller's scope is being cancelled.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lifecycle = Mutex()

    // Bumped by every start(), so a stop() queued behind a quick reopen cannot kill the new session.
    @Volatile
    private var generation = 0
    private var session: LoginSyncServer.Session? = null
    private var addresses = emptyList<NetAddress>()
    private var selected: NetAddress? = null
    private var restored = emptyList<LoginSyncService>()

    override suspend fun start() =
        lifecycle.withLock {
            server.stop()
            generation++
            _state.value = LoginSyncHostState.Starting
            val found = withContext(Dispatchers.IO) { reachableAddresses() }.map { NetAddress(it.interfaceName, it.ip) }
            if (found.isEmpty()) {
                _state.value = LoginSyncHostState.NoNetwork
                return@withLock
            }
            addresses = found
            // Keep the user's pick across a "new code" if that address is still up.
            selected = selected?.takeIf { it in found } ?: found.first()
            try {
                session = server.start()
                _state.value = waiting()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.e(TAG, "start", e)
                _state.value = LoginSyncHostState.Failed(e.message ?: e.toString())
            }
        }

    override fun select(address: NetAddress) {
        if (_state.value !is LoginSyncHostState.Waiting || address !in addresses) return
        selected = address
        _state.value = waiting()
    }

    override suspend fun expire() =
        lifecycle.withLock {
            server.stop()
            _state.value = LoginSyncHostState.Expired
        }

    override fun stop() {
        val target = generation
        scope.launch { lifecycle.withLock { if (generation == target) server.stop() } }
    }

    private fun waiting(): LoginSyncHostState.Waiting {
        val address = checkNotNull(selected)
        val invite = checkNotNull(session).invite(address.ip)
        return LoginSyncHostState.Waiting(invite.toUri(), addresses, address)
    }

    // LoginSyncServer.Handler — called from the server's request coroutines.

    override fun onHello() {
        _state.value = LoginSyncHostState.Connected
    }

    override fun onCancel() {
        _state.value = waiting()
    }

    override suspend fun onPayload(payload: ByteArray): ByteArray {
        val bundle = loginSyncJson.decodeFromString(LoginBundle.serializer(), payload.decodeToString())
        restored = store.restore(bundle)
        return loginSyncJson.encodeToString(LoginSyncAck.serializer(), LoginSyncAck(restored.map { it.name })).encodeToByteArray()
    }

    override fun onDelivered() {
        _state.value = LoginSyncHostState.Done(restored)
    }

    override fun onRejected(reason: String) {
        _state.value = LoginSyncHostState.Rejected(reason)
    }
}
