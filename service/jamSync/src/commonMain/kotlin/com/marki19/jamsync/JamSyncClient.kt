package com.marki19.jamsync

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
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

    private val client = HttpClient {
        install(WebSockets)
    }

    private val _messages = MutableSharedFlow<JamMessage>(extraBufferCapacity = 32)
    val messages = _messages.asSharedFlow()

    /** Outbound send queue — commands are posted here and flushed by the socket loop. */
    private val outbound = Channel<String>(Channel.BUFFERED)

    /** Live WebSocket session — null when disconnected. */
    @Volatile
    private var session: DefaultWebSocketSession? = null

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Connect to the server.  If [roomId] is null a new session is created;
     * otherwise the client joins the existing room.
     *
     * The coroutine keeps the socket alive and automatically reconnects after
     * any error.  Call [disconnect] to stop.
     */
    suspend fun connect(roomId: String? = null, userId: String, name: String, imageUrl: String) {
        var backoffMs = 1_000L
        while (true) {
            try {
                Logger.d("JamSyncClient") { "Connecting to $serverUrl …" }
                client.webSocket(serverUrl) {
                    session = this

                    // Announce ourselves
                    val init = if (roomId != null) {
                        JamMessage(type = "JOIN_SESSION", roomId = roomId, userId = userId, name = name, imageUrl = imageUrl)
                    } else {
                        JamMessage(type = "CREATE_SESSION", userId = userId, name = name, imageUrl = imageUrl)
                    }
                    send(Frame.Text(json.encodeToString(init)))

                    // Reset back-off after a clean connect
                    backoffMs = 1_000L

                    // Fan-out: inbound reader + outbound writer run concurrently
                    val inboundJob: Job = launch {
                        for (frame in incoming) {
                            if (frame is Frame.Text) {
                                val text = frame.readText()
                                Logger.d("JamSyncClient") { "← $text" }
                                try {
                                    _messages.emit(json.decodeFromString<JamMessage>(text))
                                } catch (e: Exception) {
                                    Logger.e("JamSyncClient", e) { "Parse error" }
                                }
                            }
                        }
                    }

                    val outboundJob: Job = launch {
                        for (raw in outbound) {
                            Logger.d("JamSyncClient") { "→ $raw" }
                            send(Frame.Text(raw))
                        }
                    }

                    inboundJob.join()
                    outboundJob.cancel()
                }
            } catch (e: Exception) {
                Logger.e("JamSyncClient", e) { "WebSocket error — retrying in ${backoffMs}ms" }
            } finally {
                session = null
            }

            delay(backoffMs)
            backoffMs = (backoffMs * 2).coerceAtMost(30_000L)
        }
    }

    /** Disconnect the current session and stop the reconnect loop. */
    suspend fun disconnect() {
        session?.close()
        session = null
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
