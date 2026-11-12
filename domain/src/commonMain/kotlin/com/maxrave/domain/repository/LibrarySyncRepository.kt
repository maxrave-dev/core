package com.maxrave.domain.repository

import com.maxrave.domain.data.model.librarysync.LibrarySyncPairingState
import com.maxrave.domain.data.model.librarysync.LibrarySyncState
import com.maxrave.domain.data.model.librarysync.PendingSettings
import com.maxrave.domain.data.model.librarysync.ReceivedLogins
import com.maxrave.domain.data.model.librarysync.SyncPeer
import com.maxrave.domain.data.model.loginsync.DesktopInfo
import com.maxrave.domain.data.model.loginsync.LoginSyncService
import com.maxrave.domain.data.model.loginsync.NetAddress
import kotlinx.coroutines.flow.StateFlow

/**
 * Library sync between a phone and the computer it is paired with (#2663): liked songs, followed
 * artists, hearted albums / playlists / podcasts, local playlists and listening history. The computer
 * keeps the log and merges; the phone drives every round. Never touches the queue or the player.
 *
 * Bound on Android ([LibrarySyncPhoneRepository]) and Desktop ([LibrarySyncHostRepository]) only.
 */
interface LibrarySyncRepository {
    val state: StateFlow<LibrarySyncState>

    /** Allowlisted settings the other device sent that differ from this one's; null when none wait. */
    val pendingSettings: StateFlow<PendingSettings?>

    /** Phone: runs one round now. Desktop: returns at once, since the phone drives every round. */
    suspend fun syncNow()

    /**
     * Leaves every pairing on this device: forgets the keys, drops the changes not yet sent, and tells
     * the other side when it can be reached. Clearing listening history calls this, so a deletion
     * never travels to the other device.
     */
    suspend fun unpairAll()

    /** Writes [pendingSettings] into this device's settings; the caller restarts the app afterwards. */
    suspend fun applyPendingSettings()

    suspend fun dismissPendingSettings()
}

/** The computer: shows the pairing QR, serves the paired phones. */
interface LibrarySyncHostRepository : LibrarySyncRepository {
    val pairing: StateFlow<LibrarySyncPairingState>

    /**
     * Sign-ins a paired phone sent, waiting for the user here to accept them; null when none wait.
     * Nothing is signed in until [answerLogins] is called with true.
     */
    val pendingLogins: StateFlow<ReceivedLogins?>

    fun answerLogins(accept: Boolean)

    /** Opens the server if a phone is paired. Call once at app start. */
    suspend fun start()

    suspend fun startPairing()

    fun selectPairingAddress(address: NetAddress)

    suspend fun stopPairing()

    suspend fun removePhone(phoneId: String)

    /**
     * Windows only: re-reads the network profile and raises [com.maxrave.domain.data.model.librarysync.LibrarySyncProblem.WINDOWS_PUBLIC_NETWORK]
     * on a Public one. Runs PowerShell, so it is meant for when the sync screen opens, never at startup.
     */
    suspend fun checkNetworkProfile()
}

/** The phone: scans the computer's QR, runs the rounds, sends sign-ins over the paired channel. */
interface LibrarySyncPhoneRepository : LibrarySyncRepository {
    fun isPairingCode(qrText: String): Boolean

    /** Reaches the computer behind [qrText] so the user can decide to trust it. Throws LibrarySyncException. */
    suspend fun reach(qrText: String): DesktopInfo

    /** Pairs with the computer [reach] found, then runs the first round. Throws LibrarySyncException. */
    suspend fun pair(): SyncPeer

    fun cancelPairing()

    /**
     * Sends [services] to the paired computer, which asks its user first; returns what it took, empty
     * when the user there declined or did not answer in time. Throws LibrarySyncException.
     */
    suspend fun sendLogins(services: Set<LoginSyncService>): List<LoginSyncService>
}
