package org.simpmusic.loginsync

import com.maxrave.ktorext.getEngine
import com.maxrave.logger.Logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch

private const val TAG = "LoginSyncClient"

/** The phone's end of the transport: one session at a time, from connect() to disconnect(). */
class LoginSyncClient {
    private val http =
        HttpClient(getEngine()) {
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = 4_000
                requestTimeoutMillis = 20_000
            }
        }

    // Owns the goodbye sent by disconnect(), which must outlive the caller's scope.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var invite: LoginSyncInvite? = null

    // The address that answered the hello; every later request goes there.
    private var host: String? = null

    /**
     * Says hello to every address in [code] at once and keeps the first whose reply opens with its
     * key — which is also what proves the computer answering is the one showing this QR.
     * Throws [LoginSyncTransportException].
     */
    suspend fun connect(code: LoginSyncInvite): DeviceName {
        // A stale session is dropped silently, not via disconnect(): its goodbye could land AFTER this
        // hello and put the computer back on its QR while the phone is still deciding.
        invite = code
        host = null
        var lastError: String? = null
        var refused: LoginSyncTransportException? = null
        val (found, reply) =
            channelFlow<Pair<String, ByteArray>> {
                code.hosts.forEach { candidate ->
                    launch {
                        val reply =
                            try {
                                call(code, candidate, LoginSyncWire.HELLO, EMPTY)
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                Logger.e(TAG, "hello $candidate failed", e)
                                lastError = e.describe()
                                if (e is LoginSyncTransportException && e.kind == LoginSyncTransportException.Kind.REFUSED) refused = e
                                null
                            }
                        if (reply != null) send(candidate to reply)
                    }
                }
            }.firstOrNull()
                // Reached, but the computer refused this code: an old QR, not a network problem.
                ?: throw refused ?: LoginSyncTransportException(LoginSyncTransportException.Kind.UNREACHABLE, lastError)
        host = found
        return LoginSyncWire.json.decodeFromString(DeviceName.serializer(), reply.decodeToString())
    }

    /** Hands [payload] to the connected computer and returns its reply. Throws [LoginSyncTransportException]. */
    suspend fun send(payload: ByteArray): ByteArray {
        val code = invite
        val target = host
        if (code == null || target == null) throw LoginSyncTransportException(LoginSyncTransportException.Kind.FAILED, "not connected")
        return try {
            call(code, target, LoginSyncWire.PAYLOAD, payload)
        } catch (e: LoginSyncTransportException) {
            throw e
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logger.e(TAG, "send failed", e)
            throw LoginSyncTransportException(LoginSyncTransportException.Kind.FAILED, e.describe())
        }
    }

    /**
     * Ends the session and, if the computer is still waiting on this phone, puts it back on its QR.
     * Returns at once: the notice goes out in the background. After a delivered payload the computer
     * answers 410 and nothing changes, so this is always safe to call.
     */
    fun disconnect() {
        val code = invite
        val target = host
        invite = null
        host = null
        if (code == null || target == null) return
        scope.launch {
            try {
                call(code, target, LoginSyncWire.CANCEL, EMPTY)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Logger.e(TAG, "cancel failed", e)
            }
        }
    }

    private suspend fun call(
        code: LoginSyncInvite,
        target: String,
        kind: String,
        plain: ByteArray,
    ): ByteArray {
        val response =
            http.post("http://$target:${code.port}${LoginSyncWire.path(kind)}") {
                setBody(LoginSyncCipher.seal(code.key, LoginSyncWire.aad(kind, reply = false), plain))
            }
        when (response.status) {
            HttpStatusCode.OK -> {
                Unit
            }

            HttpStatusCode.Forbidden, HttpStatusCode.Gone -> {
                throw LoginSyncTransportException(LoginSyncTransportException.Kind.REFUSED, "HTTP ${response.status.value}")
            }

            else -> {
                throw LoginSyncTransportException(LoginSyncTransportException.Kind.FAILED, "HTTP ${response.status.value}")
            }
        }
        return LoginSyncCipher.open(code.key, LoginSyncWire.aad(kind, reply = true), response.bodyAsBytes())
            ?: throw LoginSyncTransportException(LoginSyncTransportException.Kind.FAILED, "the computer's reply did not open with this code")
    }

    private companion object {
        val EMPTY = "{}".encodeToByteArray()
    }
}
