package com.maxrave.domain.data.model.librarysync

import com.maxrave.domain.data.model.loginsync.LoginSyncService
import com.maxrave.domain.data.model.loginsync.NetAddress

/** The other end of a pairing: the computer, seen from a phone, or one phone, seen from the computer. */
data class SyncPeer(
    val id: String,
    val name: String,
    val os: String,
    /** Epoch milliseconds of the last round with this peer. */
    val lastSyncAt: Long? = null,
)

/** Why the last round did not happen, worded so the screen can tell the user what to do. */
enum class LibrarySyncProblem {
    /** No address answered as the paired computer: it is off, asleep, or on another network. */
    UNREACHABLE,

    /** Android 17 and later: "Nearby devices" (local network access) has not been granted. */
    NEEDS_LOCAL_NETWORK_PERMISSION,

    /** The computer no longer knows this phone: it cleared its listening history or removed it. */
    REVOKED,

    /** Desktop: another program holds the sync port. */
    PORT_IN_USE,

    /** Desktop on Windows: the network profile is Public, so Windows blocks the phone. */
    WINDOWS_PUBLIC_NETWORK,

    FAILED,
}

data class LibrarySyncState(
    val peers: List<SyncPeer> = emptyList(),
    val syncing: Boolean = false,
    /** Epoch milliseconds of the last round that finished. */
    val lastSyncAt: Long? = null,
    val problem: LibrarySyncProblem? = null,
    val problemDetail: String? = null,
) {
    val isPaired: Boolean get() = peers.isNotEmpty()
}

/** Settings the other device sent that differ from this one's; applied only once the user agrees. */
data class PendingSettings(
    val fromName: String,
    val changedAt: Long,
    val count: Int,
    /** DataStore key names of the settings that differ. */
    val keys: List<String> = emptyList(),
)

/** Sign-ins a paired phone sent to this computer, waiting for the user here to accept them. */
data class ReceivedLogins(
    val fromName: String,
    val services: List<LoginSyncService>,
)

/** The computer's pairing QR, while it is shown. */
sealed interface LibrarySyncPairingState {
    data object Idle : LibrarySyncPairingState

    data object Starting : LibrarySyncPairingState

    /** [invite] is what the QR encodes; it carries [selected] only, the address the user picked. */
    data class Waiting(
        val invite: String,
        val addresses: List<NetAddress>,
        val selected: NetAddress,
    ) : LibrarySyncPairingState

    data class Paired(
        val peer: SyncPeer,
    ) : LibrarySyncPairingState

    data object NoNetwork : LibrarySyncPairingState

    data class Failed(
        val problem: LibrarySyncProblem,
        val detail: String?,
    ) : LibrarySyncPairingState
}

class LibrarySyncException(
    val problem: LibrarySyncProblem,
    val detail: String?,
) : Exception(detail)
