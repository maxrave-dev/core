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
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

private const val TAG = "LoginSyncServer"

/**
 * The Desktop's end of the transport: a one-shot Ktor server (CIO engine) between start() and stop().
 *
 * Handlers are suspend functions, so [Handler.onPayload] is a plain call and no server thread is ever
 * blocked. The JDK HttpServer this replaced ran handlers ON its dispatcher thread and joined that
 * thread in stop() — stopping from the UI thread during a payload froze the app.
 */
class LoginSyncServer(
    private val handler: Handler,
) {
    interface Handler {
        /** A phone holding this session's key said hello. */
        fun onHello()

        /** The phone backed out before sending. */
        fun onCancel()

        /** The phone's payload; the return value is sealed back as the reply. Throwing rejects it. */
        suspend fun onPayload(payload: ByteArray): ByteArray

        /** The reply to a payload has been sent: the session is spent. */
        fun onDelivered()

        /** A payload could not be taken; [reason] is shown to the user. */
        fun onRejected(reason: String)
    }

    /** What the QR needs besides an address. */
    class Session internal constructor(
        val port: Int,
        private val key: ByteArray,
    ) {
        fun invite(host: String) = LoginSyncInvite(listOf(host), port, key)
    }

    private var server: EmbeddedServer<*, *>? = null
    private var key = ByteArray(0)

    @Volatile
    private var delivered = false

    private val device by lazy { localDeviceName() }

    /** Opens a fresh session — new key, new port — replacing any previous one. */
    suspend fun start(): Session {
        stop()
        key = LoginSyncCipher.newKey()
        delivered = false
        val http =
            embeddedServer(CIO, port = 0, host = "0.0.0.0") {
                routing {
                    post(LoginSyncWire.path(LoginSyncWire.HELLO)) { handle(call, LoginSyncWire.HELLO) }
                    post(LoginSyncWire.path(LoginSyncWire.PAYLOAD)) { handle(call, LoginSyncWire.PAYLOAD) }
                    post(LoginSyncWire.path(LoginSyncWire.CANCEL)) { handle(call, LoginSyncWire.CANCEL) }
                }
            }
        http.startSuspend(wait = false)
        server = http
        // Port 0 asks the OS for a free one; this is the only way to learn which.
        return Session(http.engine.resolvedConnectors().first().port, key)
    }

    /** NonCancellable: a caller being cancelled must not leave the port open. */
    suspend fun stop() {
        val http = server ?: return
        server = null
        withContext(NonCancellable + Dispatchers.IO) { http.stopSuspend(gracePeriodMillis = 0, timeoutMillis = 500) }
    }

    private suspend fun handle(
        call: ApplicationCall,
        kind: String,
    ) {
        // A spent session answers nothing: its key has done its one job.
        if (delivered) return call.respond(HttpStatusCode.Gone)
        if ((call.request.contentLength() ?: 0) > LoginSyncWire.MAX_BODY_BYTES) {
            return reject(call, kind, HttpStatusCode.PayloadTooLarge, "the sign-ins were larger than ${LoginSyncWire.MAX_BODY_BYTES / 1024 / 1024} MB")
        }
        try {
            val plain =
                LoginSyncCipher.open(key, LoginSyncWire.aad(kind, reply = false), call.receive<ByteArray>())
                    ?: return reject(call, kind, HttpStatusCode.Forbidden, "the phone used a different code")
            val reply =
                when (kind) {
                    LoginSyncWire.HELLO -> {
                        handler.onHello()
                        LoginSyncWire.json.encodeToString(DeviceName.serializer(), withContext(Dispatchers.IO) { device }).encodeToByteArray()
                    }

                    LoginSyncWire.PAYLOAD -> {
                        handler.onPayload(plain)
                    }

                    else -> {
                        handler.onCancel()
                        "{}".encodeToByteArray()
                    }
                }
            call.respondBytes(LoginSyncCipher.seal(key, LoginSyncWire.aad(kind, reply = true), reply), ContentType.Application.OctetStream)
            if (kind == LoginSyncWire.PAYLOAD) {
                // Only once the phone has its answer.
                delivered = true
                handler.onDelivered()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.e(TAG, "handle $kind", e)
            handler.onRejected(e.message ?: e.describe())
            call.respond(HttpStatusCode.InternalServerError)
        }
    }

    // A stray request without the key must not knock a waiting QR off the screen, so a rejected hello
    // is answered but not reported; a rejected payload is.
    private suspend fun reject(
        call: ApplicationCall,
        kind: String,
        status: HttpStatusCode,
        reason: String,
    ) {
        Logger.e(TAG, "reject $kind ${status.value}: $reason")
        if (kind == LoginSyncWire.PAYLOAD) handler.onRejected(reason)
        call.respond(status)
    }
}

/** One address this computer can be reached on, labelled by the interface it belongs to. */
data class LocalAddress(
    val interfaceName: String,
    val ip: String,
)

/**
 * Every IPv4 address the phone might reach this computer on, for the user to pick from: there is no
 * way to tell from here which network the phone shares — the LAN, Wi-Fi, or a tailnet.
 *
 * Deliberately NOT limited to RFC 1918 or to broadcast interfaces. Corporate LANs number themselves
 * from other ranges (21.x on the first machine this ran on), and a Tailscale/ZeroTier/VPN interface is
 * point-to-point with a 100.x address — filtering on either reported "no network" on a machine that was
 * plainly online. Blocking: call off the UI thread.
 */
fun reachableAddresses(): List<LocalAddress> =
    runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }
        .getOrDefault(emptyList())
        .asSequence()
        // isUp and friends throw SocketException on an interface that vanished mid-scan.
        .filter { runCatching { it.isUp && !it.isLoopback && !it.isVirtual }.getOrDefault(false) }
        // Container and hypervisor bridges: their addresses exist only inside this machine.
        .filterNot { nic -> HOST_ONLY_NIC_PREFIXES.any { nic.name.startsWith(it) } }
        .flatMap { nic ->
            nic.inetAddresses
                .asSequence()
                .filterIsInstance<Inet4Address>()
                .filterNot { it.isLinkLocalAddress }
                .mapNotNull { address -> address.hostAddress?.let { LocalAddress(nic.displayName ?: nic.name, it) } }
        }.distinctBy { it.ip }
        .toList()

private val HOST_ONLY_NIC_PREFIXES = listOf("docker", "br-", "veth", "virbr", "vboxnet", "vmnet", "podman", "cni")

/** The name the user knows this computer by. Blocking on macOS (runs scutil). */
private fun localDeviceName(): DeviceName {
    val osName = System.getProperty("os.name").orEmpty()
    val isMac = osName.startsWith("Mac")
    val name =
        when {
            // The friendly name from System Settings ("Minh's MacBook Pro"), not the hostname.
            isMac -> {
                runCatching {
                    val process = ProcessBuilder("scutil", "--get", "ComputerName").start()
                    val out = process.inputStream.bufferedReader().use { it.readText() }.trim()
                    out.takeIf { process.waitFor() == 0 }
                }.getOrNull()
            }

            osName.startsWith("Windows") -> {
                System.getenv("COMPUTERNAME")
            }

            else -> {
                System.getenv("HOSTNAME") ?: runCatching { File("/etc/hostname").readText().trim() }.getOrNull()
            }
        }?.takeIf { it.isNotBlank() }
            ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
            ?: "SimpMusic Desktop"
    return DeviceName(name = name, os = if (isMac) "macOS" else osName)
}
