package com.maxrave.domain.repository

import com.maxrave.domain.data.model.loginsync.LoginSyncHostState
import com.maxrave.domain.data.model.loginsync.NetAddress
import kotlinx.coroutines.flow.StateFlow

/** The Desktop end of login sync: shows a QR, receives the phone's sign-ins. Bound on Desktop only. */
interface LoginSyncHostRepository {
    val state: StateFlow<LoginSyncHostState>

    /** Opens a fresh session — new key, new port — replacing any previous one. */
    suspend fun start()

    /** Re-points the QR at [address]; the session keeps running. */
    fun select(address: NetAddress)

    suspend fun expire()

    /**
     * Stops the session in the background and returns at once. Not suspend on purpose: callers are
     * teardown paths (a dismissed dialog, a cleared ViewModel) whose own scope is already going away.
     */
    fun stop()
}
