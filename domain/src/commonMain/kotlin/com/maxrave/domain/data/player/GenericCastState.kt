package com.maxrave.domain.data.player

/**
 * Generic Cast (Google Cast) state wrapper (no Cast SDK dependencies)
 */
data class GenericCastState(
    val isRemote: Boolean = false,
    val isConnecting: Boolean = false,
    val deviceName: String? = null,
) {
    val isActive: Boolean get() = isRemote || isConnecting

    companion object {
        val NOT_CASTING = GenericCastState()
        fun connecting(deviceName: String? = null) = GenericCastState(isConnecting = true, deviceName = deviceName)
        fun connected(deviceName: String) = GenericCastState(isRemote = true, deviceName = deviceName)
    }
}
