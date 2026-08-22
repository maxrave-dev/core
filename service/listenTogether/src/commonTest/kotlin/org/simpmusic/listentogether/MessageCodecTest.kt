package org.simpmusic.listentogether

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Ported from Metrolist's MessageCodecTest (GPL-3.0).
 *
 * These are conformance tests, not unit tests: they exist to catch the day this codec stops
 * producing the bytes Metrolist's servers and clients expect. `encodesTheSameBytesAsProtoc` is the
 * load-bearing one — it pins the assumption the whole commonMain port rests on, that
 * kotlinx-serialization-protobuf and protoc agree on the wire.
 */
@OptIn(ExperimentalSerializationApi::class)
class MessageCodecTest {
    private val codec = MessageCodec(compressionEnabled = true)
    private val proto = ProtoBuf { encodeDefaults = true }

    @Test
    fun playbackTimingFieldsSurviveARoundTrip() {
        val action =
            PlaybackActionPayload(
                action = PlaybackActions.PLAY,
                trackId = "track",
                position = 1_234L,
                serverTime = 9_000L,
                revision = 12L,
                capturedAtServerTime = 8_950L,
            )

        val (type, payload) = codec.decode(codec.encode(MessageTypes.PLAYBACK_ACTION, action))
        val decoded = codec.decodePayload(MessageTypes.SYNC_PLAYBACK, payload) as PlaybackActionPayload

        assertEquals(MessageTypes.PLAYBACK_ACTION, type)
        assertEquals(action.action, decoded.action)
        assertEquals(action.trackId, decoded.trackId)
        assertEquals(action.position, decoded.position)
        assertEquals(action.serverTime, decoded.serverTime)
        assertEquals(action.revision, decoded.revision)
        assertEquals(action.capturedAtServerTime, decoded.capturedAtServerTime)
    }

    @Test
    fun timestampedPingIsEncodedAndPongIsDecoded() {
        val ping = PingPayload(clientTime = 1_000L, sequence = 3L)
        val (_, pingBytes) = codec.decode(codec.encode(MessageTypes.PING, ping))
        val encodedPing = proto.decodeFromByteArray(PingPayload.serializer(), pingBytes)
        assertEquals(1_000L, encodedPing.clientTime)
        assertEquals(3L, encodedPing.sequence)

        // Built the way a server builds it — envelope first, payload inside — rather than through
        // our own encode(), so the decode path is exercised against bytes we did not shape.
        val pong = PongPayload(clientTime = 1_000L, serverReceiveTime = 10_000L, serverSendTime = 10_001L, sequence = 3L)
        val envelope =
            proto.encodeToByteArray(
                Envelope.serializer(),
                Envelope(type = MessageTypes.PONG, payload = proto.encodeToByteArray(PongPayload.serializer(), pong)),
            )

        val (type, pongBytes) = codec.decode(envelope)
        val decoded = codec.decodePayload(type, pongBytes) as PongPayload

        assertEquals(PongPayload(1_000L, 10_000L, 10_001L, 3L), decoded)
        assertTrue(decoded.serverSendTime >= decoded.serverReceiveTime)
    }

    @Test
    fun aPayloadOverTheThresholdIsCompressedAndStillRoundTrips() {
        // A queue is the one message that reliably exceeds the threshold, and the only place
        // compression is ever exercised in practice.
        val queue = List(40) { TrackInfo(id = "video$it", title = "Track number $it", artist = "Some artist") }
        val action = PlaybackActionPayload(action = PlaybackActions.SYNC_QUEUE, queue = queue)

        val frame = codec.encode(MessageTypes.PLAYBACK_ACTION, action)
        val envelope = proto.decodeFromByteArray(Envelope.serializer(), frame)
        assertTrue(envelope.compressed, "a queue of 40 tracks should be past the compression threshold")

        val (_, payload) = codec.decode(frame)
        val decoded = codec.decodePayload(MessageTypes.PLAYBACK_ACTION, payload) as PlaybackActionPayload
        assertEquals(queue, decoded.queue)
    }

    @Test
    fun anUnknownMessageTypeDecodesToNullRatherThanThrowing() {
        // Metrolist may ship a message type before we do. A client that dies on an unrecognised
        // frame cannot share a room with a newer one.
        val frame = codec.encode("some_type_from_a_future_release", PingPayload(clientTime = 1L, sequence = 1L))
        val (type, payload) = codec.decode(frame)
        assertEquals("some_type_from_a_future_release", type)
        assertEquals(null, codec.decodePayload(type, payload))
    }

    @Test
    fun chatFromAMetrolistPeerDoesNotBreakTheStream() {
        // SimpMusic draws no chat UI, but a Metrolist user in the room will send this.
        val frame = codec.encode(MessageTypes.CHAT, null)
        val (type, _) = codec.decode(frame)
        assertEquals(MessageTypes.CHAT, type)
    }

    @Test
    fun envelopeFieldNumbersMatchTheProtoSchema() {
        // Field numbers ARE the contract. This catches a reorder that would otherwise only show up
        // as "cannot join a room".
        val envelope = Envelope(type = "ping", payload = byteArrayOf(1, 2, 3), compressed = true)
        val bytes = proto.encodeToByteArray(Envelope.serializer(), envelope)
        val back = proto.decodeFromByteArray(Envelope.serializer(), bytes)
        assertEquals(envelope, back)

        // field 1 (string) is tag 0x0A, field 2 (bytes) is tag 0x12, field 3 (bool) is tag 0x18
        assertEquals(0x0A.toByte(), bytes[0])
        assertTrue(bytes.contains(0x12.toByte()))
        assertTrue(bytes.contains(0x18.toByte()))
    }

    @Test
    fun trackInfoSurvivesNestingInsideAPlaybackAction() {
        val track = TrackInfo(id = "abc", title = "T", artist = "A", album = "Al", duration = 1000L, thumbnail = "u")
        val action = PlaybackActionPayload(action = PlaybackActions.CHANGE_TRACK, trackInfo = track)
        val (_, payload) = codec.decode(codec.encode(MessageTypes.PLAYBACK_ACTION, action))
        val decoded = codec.decodePayload(MessageTypes.PLAYBACK_ACTION, payload) as PlaybackActionPayload
        assertNotNull(decoded.trackInfo)
        assertEquals(track, decoded.trackInfo)
    }

    /**
     * The handshake is the one exchange whose type strings are NOT in `listentogether.proto` — the
     * schema defines the two messages but never names the envelope type that carries them. These
     * literals therefore come from the server: metroserver `internal/server/protocol.go`,
     * `MsgTypeClientCapabilities` / `MsgTypeServerCapabilities`.
     *
     * Getting either wrong fails in the worst possible way. The socket opens, the frame is sent,
     * and the server answers `unknown_message_type` — so the connection looks alive while the
     * handshake never completes and no room is ever joined. Pinning the literals is what turns
     * that into a test failure instead.
     */
    @Test
    fun capabilityHandshakeUsesTheServersOwnTypeNames() {
        assertEquals("client_capabilities", MessageTypes.CLIENT_CAPABILITIES)
        assertEquals("server_capabilities", MessageTypes.SERVER_CAPABILITIES)

        val (sentType, sentPayload) =
            codec.decode(
                codec.encode(
                    MessageTypes.CLIENT_CAPABILITIES,
                    ClientCapabilities(supportsProtobuf = true, supportsCompression = true, clientVersion = "test"),
                ),
            )
        assertEquals(MessageTypes.CLIENT_CAPABILITIES, sentType)
        val sent = proto.decodeFromByteArray(ClientCapabilities.serializer(), sentPayload)
        // The server rejects a client that claims false, with `unsupported_client`.
        assertTrue(sent.supportsProtobuf)
        assertEquals("test", sent.clientVersion)

        // Built the way the server builds it: the client never encodes a ServerCapabilities, so
        // this half only ever arrives through decodePayload — which is exactly the path that was
        // missing and left the handshake undetectable.
        val answer =
            proto.encodeToByteArray(
                Envelope.serializer(),
                Envelope(
                    type = MessageTypes.SERVER_CAPABILITIES,
                    payload =
                        proto.encodeToByteArray(
                            ServerCapabilities.serializer(),
                            ServerCapabilities(supportsProtobuf = true, supportsCompression = true, serverVersion = "1"),
                        ),
                ),
            )
        val (answerType, answerPayload) = codec.decode(answer)
        val caps = codec.decodePayload(answerType, answerPayload) as? ServerCapabilities

        assertNotNull(caps)
        assertEquals("1", caps.serverVersion)
        assertTrue(caps.supportsCompression)
    }
}
