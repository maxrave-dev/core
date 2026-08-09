package com.marki19.jamsync

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.DefaultWebSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import co.touchlab.kermit.Logger

import io.ktor.client.request.get
import com.maxrave.ktorext.getEngine

@Serializable
data class JamMessage(
    val type: String,
    val roomId: String? = null,
    val userId: String? = null,
    val name: String? = null,
    val imageUrl: String? = null,
    val command: String? = null,
    val payload: JsonObject? = null
)

/**
 * WebSocket client for the Jam sync server.
 *
 * This class holds the live [DefaultWebSocketSession] so that [sendCommand] can
 * actually transmit messages over the open connection.  Reconnection with
 * exponential back-off is handled automatically.
 */
class JamSyncClient(private val serverUrl: String) {

    private val wsClient = HttpClient(getEngine()) {
        install(WebSockets) {
            pingIntervalMillis = 20_000L
        }
    }

    private val httpPingClient = HttpClient(getEngine())

    private val _messages = MutableSharedFlow<JamMessage>(extraBufferCapacity = 32)
    val messages = _messages.asSharedFlow()

    private val outbound = Channel<String>(Channel.BUFFERED)

    @Volatile
    private var session: DefaultWebSocketSession? = null

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun connect(getRoomId: () -> String?, userId: String, name: String, imageUrl: String) {
        val httpHealthUrl = serverUrl
            .replace("wss://", "https://")
            .replace("ws://", "http://")
            .trimEnd('/') + "/health"
        
        // Quick best-effort health check (max 3s). Never block the WebSocket connection indefinitely.
        try {
            Logger.d(tag = "JamSyncClient") { "Quick health ping to Render container at: $httpHealthUrl" }
            kotlinx.coroutines.withTimeout(3_000L) {
                val resp = httpPingClient.get(httpHealthUrl)
                Logger.d(tag = "JamSyncClient") { "Health ping status: ${resp.status.value}" }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
            Logger.w(tag = "JamSyncClient") { "Health ping skipped or timed out (${e.message}), proceeding to WebSocket directly." }
        }

        var backoffMs = 500L
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            try {
                Logger.d(tag = "JamSyncClient") { "Connecting to WebSocket $serverUrl …" }
                val activeSession = kotlinx.coroutines.withTimeout(15_000L) {
                    wsClient.webSocketSession(serverUrl)
                }
                
                session = activeSession
                activeSession.run {
                    // Announce ourselves
                    val currentRoomId = getRoomId()
                    val init = if (currentRoomId != null) {
                        JamMessage(type = "JOIN_SESSION", roomId = currentRoomId, userId = userId, name = name, imageUrl = imageUrl)
                    } else {
                        JamMessage(type = "CREATE_SESSION", userId = userId, name = name, imageUrl = imageUrl)
                    }
                    send(Frame.Text(json.encodeToString(init)))

                    // Reset back-off after a clean connect
                    backoffMs = 500L

                    // Fan-out: inbound reader + outbound writer run concurrently
                    val inboundJob: Job = launch {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                Logger.d(tag = "JamSyncClient") { "← $text" }
                                try {
                                    _messages.emit(json.decodeFromString<JamMessage>(text))
                                } catch (e: Exception) {
                                    Logger.e(tag = "JamSyncClient", throwable = e) { "Parse error" }
                                }
                            }
                        }
                    }

                    val outboundJob: Job = launch {
                        for (raw in outbound) {
                            Logger.d(tag = "JamSyncClient") { "→ $raw" }
                            send(Frame.Text(raw))
                        }
                    }

                    inboundJob.join()
                    outboundJob.cancel()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                Logger.e(tag = "JamSyncClient", throwable = e) { "WebSocket error — retrying in ${backoffMs}ms" }
            } finally {
                try { session?.close() } catch (e: Exception) {}
                session = null
            }

            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(3_000L)
        }
    }

    /**
     * Send a LEAVE_SESSION message directly on the live socket before tearing it down.
     * This notifies the server to destroy the room / remove the participant immediately
     * instead of leaving a zombie session that stays alive server-side.
     */
    suspend fun sendLeave() {
        val leaveMsg = json.encodeToString(JamMessage(type = "LEAVE_SESSION"))
        try {
            session?.send(Frame.Text(leaveMsg))
        } catch (e: Exception) {
            Logger.w(tag = "JamSyncClient") { "sendLeave failed (socket may already be closed): ${e.message}" }
        }
    }

    /** Disconnect the current session and stop the reconnect loop. */
    suspend fun disconnect() {
        sendLeave()
        session?.close()
        session = null
        while (outbound.tryReceive().isSuccess) {}
    }

    /**
     * Send a command to the server.  The message is enqueued in [outbound] and
     * flushed by the active socket loop — so it is safe to call even while the
     * socket is momentarily reconnecting (the message will be sent once the
     * connection is re-established).
     */
    fun sendCommand(command: String, payload: JsonObject? = null) {
        val msg = JamMessage(type = "COMMAND", command = command, payload = payload)
        outbound.trySend(json.encodeToString(msg))
    }

    /** Send an administrative message (e.g. UPDATE_PERMISSIONS, SYNC_STATE). */
    fun sendRaw(message: JamMessage) {
        outbound.trySend(json.encodeToString(message))
    }
}
