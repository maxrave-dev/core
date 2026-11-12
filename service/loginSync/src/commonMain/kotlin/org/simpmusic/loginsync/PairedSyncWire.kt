package org.simpmusic.loginsync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.io.encoding.Base64

/*
 * The paired channel library sync runs on (#2663): the same cipher and the same "the reply opens with
 * the key, so it came from the right machine" proof as login sync, made to last. Pairing hands the
 * phone a long-lived key of its own under the QR's one-time key; after that every request names the
 * phone and is sealed with that phone's key, and every knock carries a fresh nonce, since the key is
 * now used again and again. The IP is only where to knock: whoever opens the knock with the key, and
 * answers with the nonce and the computer's id, is the paired computer, whatever its address today.
 */
object PairedSyncWire {
    /** Fixed, so when the computer's address changes the phone only has to find an IP. */
    const val PORT = 48517

    /** Administratively scoped multicast group the computer listens on for the phone's probe. */
    const val GROUP = "239.255.48.17"

    const val PHONE_HEADER = "X-SimpMusic-Phone"

    // Compressed. A first round carries a year of plays and every row they point at.
    const val MAX_BODY_BYTES = 16 * 1024 * 1024
    internal const val MAX_PLAIN_BYTES = 128 * 1024 * 1024

    internal const val PAIR_HELLO = "pair-hello"
    internal const val PAIR = "pair"
    internal const val KNOCK = "knock"
    internal const val SYNC = "sync"
    internal const val UNPAIR = "unpair"
    internal const val LOGINS = "logins"
    internal const val PROBE = "probe"

    internal val PROBE_MAGIC = "SMSYNC1?".encodeToByteArray()
    internal val REPLY_MAGIC = "SMSYNC1!".encodeToByteArray()

    internal val json = Json { ignoreUnknownKeys = true }

    internal fun path(kind: String) = "/v2/$kind"

    /** Binds a sealed body to its kind, its direction and the phone it belongs to. */
    internal fun aad(
        kind: String,
        reply: Boolean,
        phoneId: String,
    ): ByteArray = "simpmusic-library-sync/v1/$kind/${if (reply) "reply" else "request"}/$phoneId".encodeToByteArray()

    private val random = SecureRandom()

    internal fun nonce(): String = ByteArray(16).also(random::nextBytes).toKeyHex()

    internal fun gzip(plain: ByteArray): ByteArray =
        ByteArrayOutputStream().use { out ->
            GZIPOutputStream(out).use { it.write(plain) }
            out.toByteArray()
        }

    /** Null past [MAX_PLAIN_BYTES]: a body that inflates without end is refused, not swallowed. */
    internal fun gunzip(packed: ByteArray): ByteArray? =
        GZIPInputStream(ByteArrayInputStream(packed)).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                if (out.size() > MAX_PLAIN_BYTES) return null
            }
            out.toByteArray()
        }
}

@Serializable
data class PhoneHello(
    val phoneId: String,
    val name: String,
    val os: String,
)

@Serializable
data class DesktopHello(
    val desktopId: String,
    val name: String,
    val os: String,
)

/** What pairing gives the phone: the computer's identity and the key that phone uses from now on. */
@Serializable
data class PairGrant(
    val desktopId: String,
    val name: String,
    val os: String,
    val keyHex: String,
    val port: Int,
    val addresses: List<String>,
)

@Serializable
internal data class Knock(
    val nonce: String,
)

@Serializable
internal data class KnockReply(
    val nonce: String,
    val desktopId: String,
    val addresses: List<String>,
    val port: Int,
    /** The computer removed this phone; told under the phone's own key so nobody else can say it. */
    val revoked: Boolean = false,
)

/** What the pairing QR carries: `simpmusic://sync-pair?h=<ip>&p=<port>&k=<base64url one-time key>`. */
class SyncPairInvite(
    val hosts: List<String>,
    val port: Int,
    internal val key: ByteArray,
) {
    fun toUri(): String = "$PREFIX?h=${hosts.joinToString(",")}&p=$port&k=${Base64.UrlSafe.encode(key)}"

    companion object {
        private const val PREFIX = "simpmusic://sync-pair"

        fun parse(text: String): SyncPairInvite? {
            if (!text.startsWith("$PREFIX?")) return null
            val params =
                text
                    .substringAfter('?')
                    .split('&')
                    .associate { it.substringBefore('=') to it.substringAfter('=', "") }
            val hosts = params["h"]?.split(',')?.filter { it.isNotBlank() }.orEmpty()
            val port = params["p"]?.toIntOrNull()?.takeIf { it in 1..65535 }
            val key = params["k"]?.let { runCatching { Base64.UrlSafe.decode(it) }.getOrNull() }
            if (hosts.isEmpty() || port == null || key?.size != LoginSyncWire.KEY_BYTES) return null
            return SyncPairInvite(hosts, port, key)
        }
    }
}

class PairedSyncException(
    val kind: Kind,
    val detail: String?,
) : Exception(detail) {
    enum class Kind {
        /** No address answered as the paired computer. */
        UNREACHABLE,

        /** The computer refused: an old pairing code, or a key it does not hold. */
        REFUSED,

        /** The computer removed this phone. */
        REVOKED,
        FAILED,
    }
}

/** A new key for one phone's pairing. */
fun newPairingKey(): ByteArray = LoginSyncCipher.newKey()

fun ByteArray.toKeyHex(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

fun String.keyFromHex(): ByteArray? {
    if (length % 2 != 0) return null
    return runCatching { ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() } }.getOrNull()
}
