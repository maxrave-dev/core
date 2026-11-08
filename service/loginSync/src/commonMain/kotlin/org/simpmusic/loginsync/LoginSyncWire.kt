package org.simpmusic.loginsync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64

/*
 * The transport between the Android sender and the Desktop host. It moves one opaque payload from the
 * phone to the computer; what the payload means (which sign-ins) is the data layer's business.
 *
 * Desktop shows a QR carrying an address, a port and a one-time AES-256 key. The phone proves it holds
 * the key by sealing a hello; Desktop answers with its own name sealed under the same key, so a machine
 * that did not show this QR cannot pose as the one the user is looking at. Every body is AES-256-GCM
 * with the message kind as associated data, so a captured hello cannot be replayed as a payload. The
 * key never crosses the network: it travels on the screen.
 */

internal object LoginSyncWire {
    const val HELLO = "hello"
    const val PAYLOAD = "logins"
    const val CANCEL = "cancel"

    // Several Google accounts, each with its full cookie twice over (header form + Netscape file).
    const val MAX_BODY_BYTES = 4 * 1024 * 1024

    const val KEY_BYTES = 32
    const val NONCE_BYTES = 12
    const val TAG_BITS = 128

    val json = Json { ignoreUnknownKeys = true }

    fun path(kind: String) = "/v1/$kind"

    /** Associated data binding a sealed body to its message kind and direction. */
    fun aad(
        kind: String,
        reply: Boolean,
    ): ByteArray = "simpmusic-login-sync/v1/$kind/${if (reply) "reply" else "request"}".encodeToByteArray()
}

/** The computer as it names itself, so the phone can ask "trust MINH-PC (Windows 11)?". */
@Serializable
data class DeviceName(
    val name: String,
    val os: String,
)

class LoginSyncTransportException(
    val kind: Kind,
    val detail: String?,
) : Exception(detail) {
    enum class Kind {
        /** No address answered with this QR's key. */
        UNREACHABLE,

        /** The computer refused this code: a different key (403) or a finished session (410). */
        REFUSED,
        FAILED,
    }
}

/** What the QR carries: `simpmusic://login-sync?h=<ip>&p=<port>&k=<base64url key>`. */
class LoginSyncInvite(
    val hosts: List<String>,
    val port: Int,
    internal val key: ByteArray,
) {
    fun toUri(): String = "$PREFIX?h=${hosts.joinToString(",")}&p=$port&k=${Base64.UrlSafe.encode(key)}"

    companion object {
        private const val PREFIX = "simpmusic://login-sync"

        /** Null for anything that is not a well-formed invite, so any other QR is simply ignored. */
        fun parse(text: String): LoginSyncInvite? {
            if (!text.startsWith("$PREFIX?")) return null
            // Split on the FIRST '=' only: the base64 key ends in padding.
            val params =
                text
                    .substringAfter('?')
                    .split('&')
                    .associate { it.substringBefore('=') to it.substringAfter('=', "") }
            val hosts = params["h"]?.split(',')?.filter { it.isNotBlank() }.orEmpty()
            val port = params["p"]?.toIntOrNull()?.takeIf { it in 1..65535 }
            val key = params["k"]?.let { runCatching { Base64.UrlSafe.decode(it) }.getOrNull() }
            if (hosts.isEmpty() || port == null || key?.size != LoginSyncWire.KEY_BYTES) return null
            return LoginSyncInvite(hosts, port, key)
        }
    }
}

/**
 * AES-256-GCM over `nonce(12) || ciphertext || tag(16)`, with [LoginSyncWire.aad] as associated data.
 * javax.crypto is usable in commonMain because this module targets Android and JVM only.
 */
internal object LoginSyncCipher {
    private val random = SecureRandom()

    fun newKey(): ByteArray = ByteArray(LoginSyncWire.KEY_BYTES).also(random::nextBytes)

    fun seal(
        key: ByteArray,
        aad: ByteArray,
        plain: ByteArray,
    ): ByteArray {
        val nonce = ByteArray(LoginSyncWire.NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(LoginSyncWire.TAG_BITS, nonce))
        cipher.updateAAD(aad)
        return nonce + cipher.doFinal(plain)
    }

    /** Null when [sealed] was not sealed with [key] for this [aad]: forged, corrupted, or another kind. */
    fun open(
        key: ByteArray,
        aad: ByteArray,
        sealed: ByteArray,
    ): ByteArray? {
        if (sealed.size < LoginSyncWire.NONCE_BYTES + LoginSyncWire.TAG_BITS / 8) return null
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val spec = GCMParameterSpec(LoginSyncWire.TAG_BITS, sealed, 0, LoginSyncWire.NONCE_BYTES)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), spec)
            cipher.updateAAD(aad)
            cipher.doFinal(sealed, LoginSyncWire.NONCE_BYTES, sealed.size - LoginSyncWire.NONCE_BYTES)
        } catch (e: GeneralSecurityException) {
            null
        }
    }
}

/** "SocketTimeoutException: …" — the type says as much as the message, and some messages are null. */
internal fun Throwable.describe(): String = listOfNotNull(this::class.simpleName, message).joinToString(": ")
