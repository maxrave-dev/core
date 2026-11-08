package com.maxrave.data.browserlogin

import com.maxrave.domain.repository.BrowserCookies
import com.maxrave.domain.repository.BrowserLoginException
import com.maxrave.domain.repository.BrowserLoginFailure
import com.maxrave.domain.repository.BrowserLoginTarget

internal class BrowserCookieRecord(
    val host: String,
    val name: String,
    val value: String,
    val path: String,
    val expiresSeconds: Long,
    val secure: Boolean,
    val httpOnly: Boolean,
) {
    fun isCurrent(nowSeconds: Long): Boolean = expiresSeconds == 0L || expiresSeconds > nowSeconds

    fun matchesMusicHost(): Boolean = matchesRequestHost(BrowserLoginTarget.YOUTUBE)

    fun matchesRequestHost(target: BrowserLoginTarget): Boolean {
        val domain = host.removePrefix(".").lowercase()
        val requestHost = if (target == BrowserLoginTarget.YOUTUBE) "music.youtube.com" else "open.spotify.com"
        return if (host.startsWith('.')) {
            requestHost == domain || requestHost.endsWith(".$domain")
        } else {
            requestHost == domain
        }
    }

    override fun toString(): String = "BrowserCookieRecord(<redacted>)"

    companion object {
        private val AUTH_NAMES = setOf("SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID", "SID", "__Secure-3PSID")

        fun domain(target: BrowserLoginTarget): String = if (target == BrowserLoginTarget.YOUTUBE) "youtube.com" else "spotify.com"

        fun isYouTubeHost(host: String): Boolean = isTargetHost(host, BrowserLoginTarget.YOUTUBE)

        fun isTargetHost(host: String, target: BrowserLoginTarget): Boolean {
            val hostDomain = host.removePrefix(".").lowercase()
            val targetDomain = domain(target)
            return hostDomain == targetDomain || hostDomain.endsWith(".$targetDomain")
        }

        fun isAuthenticationName(name: String, target: BrowserLoginTarget = BrowserLoginTarget.YOUTUBE): Boolean =
            if (target == BrowserLoginTarget.SPOTIFY) name == "sp_dc" else name in AUTH_NAMES

        fun isAllowedName(name: String, target: BrowserLoginTarget): Boolean = target != BrowserLoginTarget.SPOTIFY || name == "sp_dc"

        fun toYouTubeCookies(
            records: List<BrowserCookieRecord>,
            nowSeconds: Long = System.currentTimeMillis() / 1_000,
        ): BrowserCookies = toCookies(records, BrowserLoginTarget.YOUTUBE, nowSeconds)

        fun toSpotifyCookies(records: List<BrowserCookieRecord>, nowSeconds: Long = System.currentTimeMillis() / 1_000): BrowserCookies =
            toCookies(records, BrowserLoginTarget.SPOTIFY, nowSeconds)

        fun toCookies(records: List<BrowserCookieRecord>, target: BrowserLoginTarget, nowSeconds: Long = System.currentTimeMillis() / 1_000): BrowserCookies {
            val current = records.filter { isTargetHost(it.host, target) && isAllowedName(it.name, target) && it.isCurrent(nowSeconds) }
            current.forEach { record ->
                // A value is emitted both in a Cookie header and a tab-separated Netscape row.
                if (record.name.isBlank() || record.name.any { it <= ' ' || it in "=;," } ||
                    record.value.any { it < ' ' || it == '\u007f' || it == ';' } ||
                    record.host.any { it <= ' ' || it == '\u007f' } ||
                    !record.path.startsWith('/') || record.path.any { it < ' ' || it == '\u007f' }
                ) {
                    throw BrowserLoginException(BrowserLoginFailure.INVALID_DATA)
                }
            }
            // Existing clients turn headers into name/value maps. Resolve duplicate names before
            // handing credentials to them and preserve their original scope in the Netscape text.
            val headerRecords = current.filter { it.matchesRequestHost(target) && it.path == "/" }
                .sortedWith(compareByDescending<BrowserCookieRecord> { it.host.removePrefix(".").length }.thenBy { it.host.startsWith('.') })
                .distinctBy { it.name }
            if (headerRecords.none { isAuthenticationName(it.name, target) && it.value.isNotEmpty() }) {
                throw BrowserLoginException(BrowserLoginFailure.NO_COOKIES)
            }
            val header = headerRecords.joinToString("; ") { "${it.name}=${it.value}" }
            val netscape = buildString {
                append("# Netscape HTTP Cookie File\n")
                current.forEach {
                    if (it.httpOnly) append("#HttpOnly_")
                    append(it.host).append('\t')
                    append(if (it.host.startsWith('.')) "TRUE" else "FALSE").append('\t')
                    append(it.path).append('\t')
                    append(if (it.secure) "TRUE" else "FALSE").append('\t')
                    append(it.expiresSeconds.coerceAtLeast(0)).append('\t')
                    append(it.name).append('\t').append(it.value).append('\n')
                }
            }
            return BrowserCookies(header, netscape)
        }
    }
}
