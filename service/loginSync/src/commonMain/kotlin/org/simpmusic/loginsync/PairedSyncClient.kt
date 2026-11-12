package org.simpmusic.loginsync

import com.maxrave.ktorext.getEngine
import com.maxrave.logger.Logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException

private const val TAG = "PairedSyncClient"

/** The paired computer as the phone remembers it. */
class PairedTarget(
    val desktopId: String,
    val phoneId: String,
    internal val key: ByteArray,
    val port: Int,
    /** The address the user picked when pairing, tried first. */
    val preferredHost: String,
    val hosts: List<String>,
)

/** Where the paired computer answered this time, and the addresses it reports for next time. */
class LocatedDesktop(
    val host: String,
    val port: Int,
    val addresses: List<String>,
    val revoked: Boolean,
)

/** The phone's end of the paired channel. */
class PairedSyncClient {
    private val http =
        HttpClient(getEngine()) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 4_000
                requestTimeoutMillis = 120_000
            }
        }

    // ===== Pairing =====

    /**
     * Says hello to every address in [invite] at once and keeps the first that answers under the QR's
     * key, which also proves the computer answering is the one showing the QR.
     */
    suspend fun pairHello(
        invite: SyncPairInvite,
        phone: PhoneHello,
    ): Pair<String, DesktopHello> {
        var lastError: String? = null
        var refused = false
        val body = PairedSyncWire.json.encodeToString(PhoneHello.serializer(), phone).encodeToByteArray()
        return channelFlow {
            invite.hosts.forEach { host ->
                launch {
                    try {
                        val reply = pairCall(invite, host, PairedSyncWire.PAIR_HELLO, body)
                        send(host to PairedSyncWire.json.decodeFromString(DesktopHello.serializer(), reply.decodeToString()))
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        if (e is PairedSyncException && e.kind == PairedSyncException.Kind.REFUSED) refused = true
                        lastError = e.describe()
                        Logger.e(TAG, "pair hello $host failed", e)
                    }
                }
            }
        }.firstOrNull()
            ?: throw PairedSyncException(if (refused) PairedSyncException.Kind.REFUSED else PairedSyncException.Kind.UNREACHABLE, lastError)
    }

    suspend fun pair(
        invite: SyncPairInvite,
        host: String,
        phone: PhoneHello,
    ): PairGrant {
        val body = PairedSyncWire.json.encodeToString(PhoneHello.serializer(), phone).encodeToByteArray()
        val reply = pairCall(invite, host, PairedSyncWire.PAIR, body)
        return PairedSyncWire.json.decodeFromString(PairGrant.serializer(), reply.decodeToString())
    }

    private suspend fun pairCall(
        invite: SyncPairInvite,
        host: String,
        kind: String,
        plain: ByteArray,
    ): ByteArray {
        val response =
            http.post("http://${hostPart(host)}:${invite.port}${PairedSyncWire.path(kind)}") {
                setBody(LoginSyncCipher.seal(invite.key, PairedSyncWire.aad(kind, reply = false, phoneId = ""), plain))
            }
        when (response.status) {
            HttpStatusCode.OK -> Unit
            HttpStatusCode.Forbidden, HttpStatusCode.Gone -> throw PairedSyncException(PairedSyncException.Kind.REFUSED, "HTTP ${response.status.value}")
            else -> throw PairedSyncException(PairedSyncException.Kind.FAILED, "HTTP ${response.status.value}")
        }
        return LoginSyncCipher.open(invite.key, PairedSyncWire.aad(kind, reply = true, phoneId = ""), response.bodyAsBytes())
            ?: throw PairedSyncException(PairedSyncException.Kind.FAILED, "the computer's reply did not open with this code")
    }

    // ===== Finding the computer =====

    /**
     * Finds the paired computer wherever it is today: the address picked at pairing, then every other
     * address it last reported, then a sealed UDP probe across the LAN (multicast and broadcast), then
     * a sweep of this phone's /24. Each answer is checked: it must open with the key and echo the
     * nonce and the computer's id. Null when nothing answered as that computer.
     */
    suspend fun locate(target: PairedTarget): LocatedDesktop? {
        knock(target, target.preferredHost, quick = true)?.let { return it }
        val others = target.hosts.filter { it != target.preferredHost }.distinct()
        if (others.isNotEmpty()) race(others) { knock(target, it, quick = true) }?.let { return it }
        probe(target)?.let { return it }
        return sweep(target)
    }

    private suspend fun knock(
        target: PairedTarget,
        host: String,
        quick: Boolean,
    ): LocatedDesktop? =
        try {
            val nonce = PairedSyncWire.nonce()
            val body = PairedSyncWire.json.encodeToString(Knock.serializer(), Knock(nonce)).encodeToByteArray()
            val response =
                http.post("http://${hostPart(host)}:${target.port}${PairedSyncWire.path(PairedSyncWire.KNOCK)}") {
                    header(PairedSyncWire.PHONE_HEADER, target.phoneId)
                    setBody(LoginSyncCipher.seal(target.key, PairedSyncWire.aad(PairedSyncWire.KNOCK, false, target.phoneId), body))
                    if (quick) {
                        timeout {
                            connectTimeoutMillis = 1_500
                            requestTimeoutMillis = 4_000
                        }
                    }
                }
            if (response.status != HttpStatusCode.OK) {
                null
            } else {
                LoginSyncCipher
                    .open(target.key, PairedSyncWire.aad(PairedSyncWire.KNOCK, true, target.phoneId), response.bodyAsBytes())
                    ?.let { verified(target, it, nonce, host) }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }

    private fun verified(
        target: PairedTarget,
        plain: ByteArray,
        nonce: String,
        host: String,
    ): LocatedDesktop? {
        val reply = runCatching { PairedSyncWire.json.decodeFromString(KnockReply.serializer(), plain.decodeToString()) }.getOrNull() ?: return null
        if (reply.nonce != nonce || reply.desktopId != target.desktopId) return null
        return LocatedDesktop(host, reply.port, reply.addresses, reply.revoked)
    }

    /** One sealed UDP probe to the multicast group and every broadcast address; first verified answer wins. */
    private suspend fun probe(target: PairedTarget): LocatedDesktop? =
        withContext(Dispatchers.IO) {
            try {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    socket.soTimeout = 300
                    val nonce = PairedSyncWire.nonce()
                    val idBytes = target.phoneId.encodeToByteArray()
                    val sealed =
                        LoginSyncCipher.seal(
                            target.key,
                            PairedSyncWire.aad(PairedSyncWire.PROBE, false, target.phoneId),
                            PairedSyncWire.json.encodeToString(Knock.serializer(), Knock(nonce)).encodeToByteArray(),
                        )
                    val payload = PairedSyncWire.PROBE_MAGIC + byteArrayOf(idBytes.size.toByte()) + idBytes + sealed
                    val destinations = listOf(PairedSyncWire.GROUP, "255.255.255.255") + broadcastAddresses()
                    destinations.distinct().forEach { address ->
                        runCatching { socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName(address), target.port)) }
                    }
                    val deadline = System.currentTimeMillis() + 1_500
                    val buffer = ByteArray(8192)
                    while (System.currentTimeMillis() < deadline) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        try {
                            socket.receive(packet)
                        } catch (e: SocketTimeoutException) {
                            continue
                        }
                        val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                        val magic = PairedSyncWire.REPLY_MAGIC
                        if (data.size <= magic.size || !data.copyOfRange(0, magic.size).contentEquals(magic)) continue
                        val plain =
                            LoginSyncCipher.open(
                                target.key,
                                PairedSyncWire.aad(PairedSyncWire.PROBE, true, target.phoneId),
                                data.copyOfRange(magic.size, data.size),
                            ) ?: continue
                        val host = packet.address?.hostAddress ?: continue
                        verified(target, plain, nonce, host)?.let { return@use it }
                    }
                    null
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.e(TAG, "probe failed", e)
                null
            }
        }

    /**
     * For networks that drop multicast and broadcast: knocks on every address of this phone's /24s,
     * a few dozen at a time, and stops at the first that answers as the paired computer.
     */
    private suspend fun sweep(target: PairedTarget): LocatedDesktop? {
        val own = ownAddresses()
        val candidates =
            own
                // Never the carrier's network or a tunnel: only the networks a computer could be on.
                .filterNot { it.startsWith("100.") && it.split('.').getOrNull(1)?.toIntOrNull() in 64..127 }
                .map { it.substringBeforeLast('.') }
                .distinct()
                .flatMap { prefix -> (1..254).map { "$prefix.$it" } }
                .filterNot { it in own }
        if (candidates.isEmpty()) return null
        val gate = Semaphore(48)
        return race(candidates) { host -> gate.withPermit { knock(target, host, quick = true) } }
    }

    private suspend fun <T> race(
        items: List<String>,
        attempt: suspend (String) -> T?,
    ): T? =
        channelFlow<T> {
            items.forEach { item -> launch { attempt(item)?.let { send(it) } } }
        }.firstOrNull()

    // ===== Paired calls =====

    suspend fun sync(
        target: PairedTarget,
        at: LocatedDesktop,
        request: ByteArray,
    ): ByteArray {
        val reply = pairedCall(target, at, PairedSyncWire.SYNC, PairedSyncWire.gzip(request))
        return PairedSyncWire.gunzip(reply) ?: throw PairedSyncException(PairedSyncException.Kind.FAILED, "reply too large")
    }

    suspend fun logins(
        target: PairedTarget,
        at: LocatedDesktop,
        payload: ByteArray,
    ): ByteArray = pairedCall(target, at, PairedSyncWire.LOGINS, payload)

    suspend fun unpair(
        target: PairedTarget,
        at: LocatedDesktop,
    ) {
        val body = PairedSyncWire.json.encodeToString(Knock.serializer(), Knock(PairedSyncWire.nonce())).encodeToByteArray()
        pairedCall(target, at, PairedSyncWire.UNPAIR, body)
    }

    private suspend fun pairedCall(
        target: PairedTarget,
        at: LocatedDesktop,
        kind: String,
        plain: ByteArray,
    ): ByteArray {
        val response =
            try {
                http.post("http://${hostPart(at.host)}:${at.port}${PairedSyncWire.path(kind)}") {
                    header(PairedSyncWire.PHONE_HEADER, target.phoneId)
                    setBody(LoginSyncCipher.seal(target.key, PairedSyncWire.aad(kind, false, target.phoneId), plain))
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                throw PairedSyncException(PairedSyncException.Kind.UNREACHABLE, e.describe())
            }
        when (response.status) {
            HttpStatusCode.OK -> Unit
            HttpStatusCode.Forbidden -> throw PairedSyncException(PairedSyncException.Kind.REFUSED, "HTTP 403")
            else -> throw PairedSyncException(PairedSyncException.Kind.FAILED, "HTTP ${response.status.value}")
        }
        return LoginSyncCipher.open(target.key, PairedSyncWire.aad(kind, true, target.phoneId), response.bodyAsBytes())
            ?: throw PairedSyncException(PairedSyncException.Kind.FAILED, "the computer's reply did not open with this phone's key")
    }

    private fun hostPart(host: String) = if (':' in host) "[$host]" else host

    private companion object {
        // Cellular, VPN and tunnel interfaces on Android and desktops: sweeping them would knock on strangers.
        val NOT_A_LAN = listOf("rmnet", "ccmni", "pdp", "v4-", "tun", "ppp", "dummy", "tailscale", "utun", "wg")
    }

    private fun interfaces(): List<NetworkInterface> =
        runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }
            .getOrDefault(emptyList())
            .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }

    private fun ownAddresses(): List<String> =
        interfaces()
            .filterNot { nic -> NOT_A_LAN.any { nic.name.startsWith(it) } }
            .flatMap { nic -> nic.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .filterNot { it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress }

    private fun broadcastAddresses(): List<String> =
        interfaces()
            .flatMap { nic -> nic.interfaceAddresses.orEmpty() }
            .mapNotNull { it.broadcast?.hostAddress }
}
