package org.simpmusic.listentogether

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Talks to the real public server.
 *
 * Everything else in this module is a conformance test against bytes we shaped ourselves, which
 * cannot falsify the assumption the whole port rests on: that
 * `kotlinx-serialization-protobuf` + `@ProtoNumber` produce what `protoc` produces, and that the
 * two handshake type strings are the ones the server actually answers to. Only a live server can
 * say. It creates one empty room and leaves; the server reaps empty rooms after 5 minutes.
 *
 * Network-dependent and it touches someone else's server, so it is NOT part of the normal suite.
 * **Delete the `@Ignore` to run it** whenever the protocol layer changes.
 *
 * Last run 2026-08-22 against `wss://metroserverx.meowery.eu/ws`, all three checks green:
 * handshake answered (`serverVersion=1`, compression on), `room_created` returned a real code and
 * session token — so the server parsed bytes this module encoded — and a pong calibrated the clock.
 */
class LiveServerHandshakeTest {
    @Ignore
    @Test
    fun handshakeAndRoomCreationAgainstTheRealServer() =
        runBlocking {
            val client = ListenTogetherClient(clientVersion = "simpmusic-live-test")
            val received = Channel<ListenTogetherEvent>(Channel.UNLIMITED)
            val collector = launch { client.events.collect { received.send(it) } }

            // Events already pulled off the channel while waiting for something else. Without this
            // the helper eats them: ClockReady legitimately arrives before room_created, and a
            // later wait for it would then block forever on an event that already happened.
            val seen = mutableListOf<ListenTogetherEvent>()

            suspend fun await(
                label: String,
                predicate: (ListenTogetherEvent) -> Boolean,
            ): ListenTogetherEvent? {
                seen.firstOrNull(predicate)?.let { return it }
                return withTimeoutOrNull(20_000) {
                    for (event in received) {
                        println("  ← $event")
                        seen += event
                        if (predicate(event)) return@withTimeoutOrNull event
                    }
                    null
                }.also { if (it == null) println("  ✗ timed out waiting for $label") }
            }

            try {
                // The collector must be subscribed before the socket opens; events are not replayed.
                delay(200)
                println("→ connecting to ${ListenTogetherClient.DEFAULT_SERVER_URL}")
                client.connect()

                // 1. Proves both handshake type strings are the ones the server answers to.
                val connected = await("server_capabilities") { it is ListenTogetherEvent.Connected }
                assertNotNull(connected, "no handshake — the server never sent server_capabilities")
                connected as ListenTogetherEvent.Connected
                println("✓ handshake ok — server version '${connected.serverVersion}', compression ${connected.compressionEnabled}")

                // 2. Proves the server can PARSE bytes we encoded, i.e. protobuf equivalence holds.
                println("→ create_room")
                assertTrue(
                    client.send(MessageTypes.CREATE_ROOM, CreateRoomPayload(username = "SimpMusicTest")),
                    "create_room could not be sent",
                )
                val created =
                    await("room_created") {
                        it is ListenTogetherEvent.Message && it.type == MessageTypes.ROOM_CREATED
                    }
                assertNotNull(created, "server did not answer room_created — it could not read our bytes")
                val room = (created as ListenTogetherEvent.Message).payload as? RoomCreatedPayload
                assertNotNull(room, "room_created arrived but did not decode")
                println("✓ room created — code '${room.roomCode}', userId '${room.userId}'")
                assertTrue(room.roomCode.isNotEmpty(), "empty room code")
                assertTrue(room.sessionToken.isNotEmpty(), "empty session token")

                // 3. Proves ping/pong round trips and ServerClock can calibrate from them.
                val clock = await("ClockReady") { it is ListenTogetherEvent.ClockReady }
                assertNotNull(clock, "no pong ever calibrated the clock")
                println("✓ server clock calibrated — serverNow=${client.serverNow()}")

                client.send(MessageTypes.LEAVE_ROOM, LeaveRoomPayload())
                delay(300)
            } finally {
                collector.cancel()
                client.release()
            }
        }
}
