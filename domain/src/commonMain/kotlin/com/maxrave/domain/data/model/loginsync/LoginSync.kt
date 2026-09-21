package com.maxrave.domain.data.model.loginsync

/** A sign-in the phone can hand to the desktop. Only sign-ins travel — never settings or API keys. */
enum class LoginSyncService(
    val label: String,
) {
    YOUTUBE("YouTube Music"),
    SPOTIFY("Spotify"),
    DISCORD("Discord"),
    LASTFM("Last.fm"),
}

/** The computer the phone is about to trust, as the computer names itself. */
data class DesktopInfo(
    val name: String,
    val os: String,
)

/** One address the computer can be reached on, labelled by the interface it belongs to. */
data class NetAddress(
    val interfaceName: String,
    val ip: String,
)

sealed interface LoginSyncHostState {
    data object Starting : LoginSyncHostState

    /** [invite] is what the QR encodes; it carries [selected] only — the user picks the address. */
    data class Waiting(
        val invite: String,
        val addresses: List<NetAddress>,
        val selected: NetAddress,
    ) : LoginSyncHostState

    /** A phone holding this QR's key has said hello and is choosing what to send. */
    data object Connected : LoginSyncHostState

    data class Done(
        val services: List<LoginSyncService>,
    ) : LoginSyncHostState

    data object Expired : LoginSyncHostState

    /** The phone got through, but what it sent could not be taken. */
    data class Rejected(
        val reason: String,
    ) : LoginSyncHostState

    data object NoNetwork : LoginSyncHostState

    data class Failed(
        val message: String,
    ) : LoginSyncHostState
}

class LoginSyncException(
    val reason: Reason,
    val detail: String?,
) : Exception(detail) {
    enum class Reason {
        /** No address in the QR answered with this QR's key. */
        UNREACHABLE,

        /** The computer no longer accepts this code: it expired, or another phone finished first. */
        CODE_EXPIRED,
        FAILED,
    }
}
