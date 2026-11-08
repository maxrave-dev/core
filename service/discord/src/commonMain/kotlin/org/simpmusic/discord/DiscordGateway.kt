package org.simpmusic.discord

import com.maxrave.ktorext.getEngine
import com.maxrave.logger.Logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.concurrent.Volatile
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Discord's gateway, spoken with an OAuth Bearer token. Rich Presence needs nothing from the gateway
 * beyond a live, identified session, so this only keeps one alive: heartbeat, resume after a drop,
 * and back off between attempts.
 *
 * The close code decides whether to try again, like kizzy's gateway does for the FOSS build (#2157):
 * 4004 (authentication failed) gets one retry with a refreshed token, 4010-4014 are setup errors that
 * no retry can fix, and anything else is a dropped connection worth reconnecting. After giving up it
 * stays down until [close], so a rejected token cannot turn into a reconnect loop.
 */
internal class DiscordGateway(
    private val tokenProvider: suspend (forceRefresh: Boolean) -> String?,
) : CoroutineScope {
    override val coroutineContext: CoroutineContext = SupervisorJob() + Dispatchers.Default

    private val client = HttpClient(getEngine()) { install(WebSockets) }
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Volatile private var session: DefaultClientWebSocketSession? = null

    @Volatile private var ready = false

    @Volatile private var gaveUp = false

    @Volatile private var sequence: Int? = null
    private var connectionJob: Job? = null

    // Only touched by the connection loop.
    private var sessionId: String? = null
    private var resumeGatewayUrl: String? = null

    fun isConnected(): Boolean = connectionJob?.isActive == true

    fun close() {
        connectionJob?.cancel()
        connectionJob = null
        ready = false
        gaveUp = false
        sequence = null
        sessionId = null
        resumeGatewayUrl = null
    }

    /** Waits up to [READY_TIMEOUT] for an identified session, then sends; drops the update otherwise. */
    suspend fun sendPresence(presence: Presence) {
        if (gaveUp) return
        if (connectionJob?.isActive != true) {
            connectionJob = launch { runConnectionLoop() }
        }
        val isReady =
            withTimeoutOrNull(READY_TIMEOUT) {
                while (!ready) delay(100.milliseconds)
                true
            } ?: false
        if (!isReady) {
            Logger.w(TAG, "Gateway not ready within $READY_TIMEOUT, dropping the presence update")
            return
        }
        send(GatewayPayload(op = OP_PRESENCE_UPDATE, d = json.encodeToJsonElement(Presence.serializer(), presence)))
    }

    private suspend fun runConnectionLoop() {
        var reconnectDelay = INITIAL_RECONNECT_DELAY
        var authRetried = false
        while (true) {
            val outcome =
                try {
                    connectOnce()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Logger.w(TAG, "Gateway connection failed: ${e.message}")
                    Outcome(CODE_UNKNOWN, reachedReady = false)
                }
            ready = false
            if (outcome.reachedReady) {
                reconnectDelay = INITIAL_RECONNECT_DELAY
                authRetried = false
            }
            when (val code = outcome.closeCode) {
                CODE_NO_TOKEN -> {
                    Logger.w("Auth", "Discord: no access token, not connecting to the gateway")
                    gaveUp = true
                    return
                }

                CODE_RECONNECT_NOW -> continue

                CLOSE_AUTHENTICATION_FAILED -> {
                    if (authRetried) {
                        Logger.e("Auth", "Discord: the gateway rejected the refreshed token too (4004), not reconnecting")
                        gaveUp = true
                        return
                    }
                    authRetried = true
                    sessionId = null
                    resumeGatewayUrl = null
                    sequence = null
                    if (tokenProvider(true) == null) {
                        Logger.e("Auth", "Discord: the gateway rejected the token (4004) and it could not be refreshed")
                        gaveUp = true
                        return
                    }
                }

                in FATAL_CLOSE_CODES -> {
                    Logger.e("Auth", "Discord: gateway closed with non-recoverable code $code, not reconnecting")
                    gaveUp = true
                    return
                }

                else -> {
                    delay(reconnectDelay)
                    reconnectDelay = (reconnectDelay * 2).coerceAtMost(MAX_RECONNECT_DELAY)
                }
            }
        }
    }

    private suspend fun connectOnce(): Outcome {
        val token = tokenProvider(false) ?: return Outcome(CODE_NO_TOKEN, reachedReady = false)
        val url = resumeGatewayUrl?.let { "$it/?v=10&encoding=json" } ?: GATEWAY_URL
        val live = client.webSocketSession(url)
        session = live
        var reachedReady = false
        var forcedCode: Int? = null
        try {
            coroutineScope {
                var heartbeat: Job? = null
                frames@ for (frame in live.incoming) {
                    if (frame !is Frame.Text) continue
                    val payload = json.decodeFromString(GatewayPayload.serializer(), frame.readText())
                    payload.s?.let { sequence = it }
                    when (payload.op) {
                        OP_HELLO -> {
                            val hello = json.decodeFromJsonElement(Hello.serializer(), payload.d ?: JsonNull)
                            heartbeat?.cancel()
                            heartbeat = launch { heartbeatLoop(hello.heartbeatInterval) }
                            val id = sessionId
                            val seq = sequence
                            if (id != null && seq != null) {
                                send(OP_RESUME, json.encodeToJsonElement(Resume.serializer(), Resume("Bearer $token", id, seq)))
                            } else {
                                val identify = Identify(token = "Bearer $token", properties = identifyProperties())
                                send(OP_IDENTIFY, json.encodeToJsonElement(Identify.serializer(), identify))
                            }
                        }

                        OP_DISPATCH -> {
                            when (payload.t) {
                                "READY" -> {
                                    val readyEvent = json.decodeFromJsonElement(Ready.serializer(), payload.d ?: JsonNull)
                                    sessionId = readyEvent.sessionId
                                    resumeGatewayUrl = readyEvent.resumeGatewayUrl
                                    ready = true
                                    reachedReady = true
                                    Logger.i(TAG, "Gateway ready")
                                }

                                "RESUMED" -> {
                                    ready = true
                                    reachedReady = true
                                    Logger.i(TAG, "Gateway resumed")
                                }
                            }
                        }

                        OP_HEARTBEAT -> sendHeartbeat()

                        OP_RECONNECT -> {
                            forcedCode = CODE_RECONNECT_NOW
                            break@frames
                        }

                        OP_INVALID_SESSION -> {
                            if ((payload.d as? JsonPrimitive)?.booleanOrNull != true) {
                                sessionId = null
                                resumeGatewayUrl = null
                                sequence = null
                            }
                            // Discord asks for a pause of 1-5 seconds before identifying again.
                            delay(Random.nextLong(1_000, 5_000))
                            forcedCode = CODE_RECONNECT_NOW
                            break@frames
                        }
                    }
                }
                heartbeat?.cancel()
            }
            forcedCode?.let { return Outcome(it, reachedReady) }
            val code = live.closeReason.await()?.code?.toInt() ?: CODE_UNKNOWN
            Logger.w(TAG, "Gateway closed with code $code")
            return Outcome(code, reachedReady)
        } finally {
            session = null
            ready = false
            // NonCancellable: close() is a suspend call, and on the close() path this job is
            // already cancelled, so the close frame would otherwise never be sent.
            withContext(NonCancellable) { runCatching { live.close() } }
        }
    }

    private suspend fun heartbeatLoop(intervalMs: Long) {
        // Discord asks for the first heartbeat after interval * jitter, so clients do not all beat at once.
        delay((intervalMs * Random.nextDouble()).toLong())
        while (true) {
            sendHeartbeat()
            delay(intervalMs)
        }
    }

    private suspend fun sendHeartbeat() {
        send(GatewayPayload(op = OP_HEARTBEAT, d = sequence?.let { JsonPrimitive(it) } ?: JsonNull))
    }

    private suspend fun send(
        op: Int,
        d: JsonElement,
    ) = send(GatewayPayload(op = op, d = d))

    private suspend fun send(payload: GatewayPayload) {
        val live = session ?: return
        try {
            live.send(json.encodeToString(GatewayPayload.serializer(), payload))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w(TAG, "Gateway send failed for op ${payload.op}: ${e.message}")
        }
    }

    // The same identify properties Metrolist sends with a Bearer token.
    private fun identifyProperties() =
        IdentifyProperties(
            os = "android",
            browser = "Discord Android",
            device = discordClientId(),
        )

    private class Outcome(
        val closeCode: Int,
        val reachedReady: Boolean,
    )

    private companion object {
        const val TAG = "DiscordGateway"
        const val GATEWAY_URL = "wss://gateway.discord.gg/?v=10&encoding=json"

        const val OP_DISPATCH = 0
        const val OP_HEARTBEAT = 1
        const val OP_IDENTIFY = 2
        const val OP_PRESENCE_UPDATE = 3
        const val OP_RESUME = 6
        const val OP_RECONNECT = 7
        const val OP_INVALID_SESSION = 9
        const val OP_HELLO = 10

        const val CLOSE_AUTHENTICATION_FAILED = 4004

        // 4010 invalid shard, 4011 sharding required, 4012 invalid API version, 4013 invalid
        // intents, 4014 disallowed intents.
        val FATAL_CLOSE_CODES = setOf(4010, 4011, 4012, 4013, 4014)

        // Not close codes: what the loop does when there was no close frame to read.
        const val CODE_UNKNOWN = -1
        const val CODE_NO_TOKEN = -2
        const val CODE_RECONNECT_NOW = -3

        val READY_TIMEOUT = 30.seconds
        val INITIAL_RECONNECT_DELAY = 1.seconds
        val MAX_RECONNECT_DELAY = 60.seconds
    }
}
