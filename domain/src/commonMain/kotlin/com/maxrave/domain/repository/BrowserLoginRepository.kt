package com.maxrave.domain.repository

/** Desktop-only source of sign-ins. Discovery never requests an OS storage key. */
interface BrowserLoginRepository {
    suspend fun profiles(target: BrowserLoginTarget = BrowserLoginTarget.YOUTUBE): List<BrowserProfile>

    /** Reads only the selected service; Spotify imports contain sp_dc alone. */
    suspend fun cookies(profile: BrowserProfile, target: BrowserLoginTarget): BrowserCookies

    /** Reads only YouTube cookies from the explicitly selected profile. */
    suspend fun youTubeCookies(profile: BrowserProfile): BrowserCookies = cookies(profile, BrowserLoginTarget.YOUTUBE)
}

enum class BrowserLoginTarget { YOUTUBE, SPOTIFY }

data class BrowserProfile(
    val id: String,
    val browser: String,
    val name: String,
    val status: BrowserProfileStatus,
)

/** Cookie presence is a hint, not proof that the service will accept the session. */
enum class BrowserProfileStatus {
    COOKIES_FOUND,
    NO_COOKIES,
    PERMISSION_REQUIRED,
    UNREADABLE,
    UNSUPPORTED,
}

/** Not a data class: generated toString/copy must not expose credential values. */
class BrowserCookies(
    val cookie: String,
    val netscapeCookie: String,
) {
    override fun toString(): String = "BrowserCookies(<redacted>)"
}

enum class BrowserLoginFailure {
    FILE_ACCESS_DENIED,
    ACCESS_DENIED,
    KEY_UNAVAILABLE,
    UNSUPPORTED_ENCRYPTION,
    DATABASE_BUSY,
    INVALID_DATA,
    NO_COOKIES,
}

/** Public errors contain a category only, never native errors, paths, cookie values or keys. */
class BrowserLoginException(
    val reason: BrowserLoginFailure,
) : Exception(reason.name)
