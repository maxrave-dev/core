package org.simpmusic.loginsync

import com.maxrave.logger.Logger
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.SocketException
import kotlin.concurrent.thread

private const val TAG = "PairedSyncServer"

/**
 * The computer's end of the paired channel: one Ktor server (CIO) on [PairedSyncWire.PORT] and a UDP
 * socket on the same port answering the phone's probe. Runs while a phone is paired or the pairing
 * QR is shown, nothing more. Every route but pairing names a phone and is sealed with that phone's
 * key; anything that does not open is refused without saying why.
 */
class PairedSyncServer(
    private val handler: Handler,
) {
    interface Handler {
        /** This computer's sync identity; known before the server starts. */
        fun desktopId(): String

        /** The one-time key of the pairing QR shown right now, if one is. */
        fun pairingKey(): ByteArray?

        /** A phone holding the QR's key asks to pair; returns the key that phone uses from now on. */
        suspend fun onPair(phone: PhoneHello): ByteArray

        fun keyFor(phoneId: String): ByteArray?

        /** A phone removed while away: its old key, so it can be told under it. */
        fun revokedKeyFor(phoneId: String): ByteArray?

        suspend fun onRevokedTold(phoneId: String)

        suspend fun onSync(
            phoneId: String,
            request: ByteArray,
        ): ByteArray

        suspend fun onUnpair(phoneId: String)

        suspend fun onLogins(
            phoneId: String,
            payload: ByteArray,
        ): ByteArray
    }

    private var http: EmbeddedServer<*, *>? = null
    private var udp: MulticastSocket? = null

    private val device by lazy { localDeviceName() }

    val isRunning: Boolean get() = http != null

    /** Throws java.net.BindException when another program holds the port. */
    suspend fun start() {
        if (http != null) return
        // CIO binds in the background and a refused bind only reaches its log, so the port is tried
        // here first, where a BindException can reach the caller.
        withContext(Dispatchers.IO) {
            ServerSocket().use {
                it.reuseAddress = true
                it.bind(InetSocketAddress(PairedSyncWire.PORT))
            }
        }
        val server =
            embeddedServer(CIO, port = PairedSyncWire.PORT, host = "0.0.0.0") {
                routing {
                    post(PairedSyncWire.path(PairedSyncWire.PAIR_HELLO)) { pairing(call, PairedSyncWire.PAIR_HELLO) }
                    post(PairedSyncWire.path(PairedSyncWire.PAIR)) { pairing(call, PairedSyncWire.PAIR) }
                    post(PairedSyncWire.path(PairedSyncWire.KNOCK)) { knock(call) }
                    post(PairedSyncWire.path(PairedSyncWire.SYNC)) { paired(call, PairedSyncWire.SYNC) }
                    post(PairedSyncWire.path(PairedSyncWire.UNPAIR)) { paired(call, PairedSyncWire.UNPAIR) }
                    post(PairedSyncWire.path(PairedSyncWire.LOGINS)) { paired(call, PairedSyncWire.LOGINS) }
                }
            }
        withContext(Dispatchers.IO) { server.startSuspend(wait = false) }
        http = server
        startProbeListener()
        Logger.i(TAG, "listening on ${PairedSyncWire.PORT}")
    }

    /** NonCancellable: a caller being cancelled must not leave the port open. */
    suspend fun stop() {
        udp?.close()
        udp = null
        val server = http ?: return
        http = null
        withContext(NonCancellable + Dispatchers.IO) { server.stopSuspend(gracePeriodMillis = 0, timeoutMillis = 1_000) }
        Logger.i(TAG, "stopped")
    }

    private suspend fun pairing(
        call: ApplicationCall,
        kind: String,
    ) {
        val key = handler.pairingKey() ?: return call.respond(HttpStatusCode.Gone)
        val plain =
            readBody(call)?.let { LoginSyncCipher.open(key, PairedSyncWire.aad(kind, false, ""), it) }
                ?: return call.respond(HttpStatusCode.Forbidden)
        try {
            val phone = PairedSyncWire.json.decodeFromString(PhoneHello.serializer(), plain.decodeToString())
            val reply =
                if (kind == PairedSyncWire.PAIR_HELLO) {
                    PairedSyncWire.json.encodeToString(DesktopHello.serializer(), DesktopHello(handler.desktopId(), device.name, device.os))
                } else {
                    val phoneKey = handler.onPair(phone)
                    val grant =
                        PairGrant(
                            desktopId = handler.desktopId(),
                            name = device.name,
                            os = device.os,
                            keyHex = phoneKey.toKeyHex(),
                            port = PairedSyncWire.PORT,
                            addresses = withContext(Dispatchers.IO) { reachableAddresses() }.map { it.ip },
                        )
                    PairedSyncWire.json.encodeToString(PairGrant.serializer(), grant)
                }
            respondSealed(call, key, kind, "", reply.encodeToByteArray())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.e(TAG, "pairing $kind", e)
            call.respond(HttpStatusCode.InternalServerError)
        }
    }

    private suspend fun knock(call: ApplicationCall) {
        val phoneId = call.request.headers[PairedSyncWire.PHONE_HEADER] ?: return call.respond(HttpStatusCode.Forbidden)
        val active = handler.keyFor(phoneId)
        val key = active ?: handler.revokedKeyFor(phoneId) ?: return call.respond(HttpStatusCode.Forbidden)
        val knock =
            readBody(call)
                ?.let { LoginSyncCipher.open(key, PairedSyncWire.aad(PairedSyncWire.KNOCK, false, phoneId), it) }
                ?.let { runCatching { PairedSyncWire.json.decodeFromString(Knock.serializer(), it.decodeToString()) }.getOrNull() }
                ?: return call.respond(HttpStatusCode.Forbidden)
        val reply = knockReply(knock.nonce, revoked = active == null)
        respondSealed(call, key, PairedSyncWire.KNOCK, phoneId, reply)
        if (active == null) handler.onRevokedTold(phoneId)
    }

    private suspend fun paired(
        call: ApplicationCall,
        kind: String,
    ) {
        val phoneId = call.request.headers[PairedSyncWire.PHONE_HEADER] ?: return call.respond(HttpStatusCode.Forbidden)
        val key = handler.keyFor(phoneId) ?: return call.respond(HttpStatusCode.Forbidden)
        val plain =
            readBody(call)?.let { LoginSyncCipher.open(key, PairedSyncWire.aad(kind, false, phoneId), it) }
                ?: return call.respond(HttpStatusCode.Forbidden)
        try {
            val reply =
                when (kind) {
                    PairedSyncWire.SYNC -> {
                        val request = PairedSyncWire.gunzip(plain) ?: return call.respond(HttpStatusCode.PayloadTooLarge)
                        PairedSyncWire.gzip(handler.onSync(phoneId, request))
                    }

                    PairedSyncWire.LOGINS -> {
                        handler.onLogins(phoneId, plain)
                    }

                    else -> {
                        plain
                    }
                }
            respondSealed(call, key, kind, phoneId, reply)
            // After the reply: the phone must hear it was taken before its key is forgotten.
            if (kind == PairedSyncWire.UNPAIR) handler.onUnpair(phoneId)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.e(TAG, "paired $kind", e)
            call.respond(HttpStatusCode.InternalServerError)
        }
    }

    // A body with no length (chunked) is refused before anything is read: it could go on forever, and
    // nothing has been authenticated yet. The app's own client always sends one.
    private suspend fun readBody(call: ApplicationCall): ByteArray? {
        val length = call.request.contentLength() ?: return null
        if (length > PairedSyncWire.MAX_BODY_BYTES) return null
        return runCatching { call.receive<ByteArray>() }.getOrNull()
    }

    private suspend fun respondSealed(
        call: ApplicationCall,
        key: ByteArray,
        kind: String,
        phoneId: String,
        plain: ByteArray,
    ) = call.respondBytes(LoginSyncCipher.seal(key, PairedSyncWire.aad(kind, true, phoneId), plain), ContentType.Application.OctetStream)

    private fun knockReply(
        nonce: String,
        revoked: Boolean,
    ): ByteArray =
        PairedSyncWire.json
            .encodeToString(
                KnockReply.serializer(),
                KnockReply(nonce, handler.desktopId(), reachableAddresses().map { it.ip }, PairedSyncWire.PORT, revoked),
            ).encodeToByteArray()

    // ===== UDP probe =====

    private fun startProbeListener() {
        val socket =
            try {
                MulticastSocket(PairedSyncWire.PORT)
            } catch (e: Exception) {
                // The phone still finds this computer by its last address or by sweeping the LAN.
                Logger.e(TAG, "probe socket unavailable", e)
                return
            }
        val group = InetSocketAddress(InetAddress.getByName(PairedSyncWire.GROUP), 0)
        runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }
            .getOrDefault(emptyList())
            .filter { runCatching { it.isUp && it.supportsMulticast() && !it.isLoopback }.getOrDefault(false) }
            .forEach { nic -> runCatching { socket.joinGroup(group, nic) } }
        udp = socket
        thread(isDaemon = true, name = "LibrarySync-Probe") {
            val buffer = ByteArray(4096)
            while (!socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketException) {
                    break
                }
                runCatching { answerProbe(socket, packet) }.onFailure { Logger.e(TAG, "probe answer failed", it) }
            }
        }
    }

    private fun answerProbe(
        socket: MulticastSocket,
        packet: DatagramPacket,
    ) {
        val data = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
        val magic = PairedSyncWire.PROBE_MAGIC
        if (data.size <= magic.size + 1 || !data.copyOfRange(0, magic.size).contentEquals(magic)) return
        val idLength = data[magic.size].toInt() and 0xff
        val idEnd = magic.size + 1 + idLength
        if (data.size <= idEnd) return
        val phoneId = data.copyOfRange(magic.size + 1, idEnd).decodeToString()
        val active = handler.keyFor(phoneId)
        val key = active ?: handler.revokedKeyFor(phoneId) ?: return
        val plain = LoginSyncCipher.open(key, PairedSyncWire.aad(PairedSyncWire.PROBE, false, phoneId), data.copyOfRange(idEnd, data.size)) ?: return
        val knock = PairedSyncWire.json.decodeFromString(Knock.serializer(), plain.decodeToString())
        val sealed = LoginSyncCipher.seal(key, PairedSyncWire.aad(PairedSyncWire.PROBE, true, phoneId), knockReply(knock.nonce, active == null))
        val reply = PairedSyncWire.REPLY_MAGIC + sealed
        socket.send(DatagramPacket(reply, reply.size, packet.socketAddress))
    }
}
