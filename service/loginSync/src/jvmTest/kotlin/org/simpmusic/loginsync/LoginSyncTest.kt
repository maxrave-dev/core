package org.simpmusic.loginsync

import kotlinx.coroutines.runBlocking
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoginSyncTest {
    @Test
    fun inviteSurvivesTheQr() {
        val key = LoginSyncCipher.newKey()
        val parsed = assertNotNull(LoginSyncInvite.parse(LoginSyncInvite(listOf("192.168.1.5"), 51234, key).toUri()))
        assertEquals(listOf("192.168.1.5"), parsed.hosts)
        assertEquals(51234, parsed.port)
        assertContentEquals(key, parsed.key)
    }

    @Test
    fun anyOtherQrIsIgnored() {
        assertNull(LoginSyncInvite.parse("https://simpmusic.org"))
        assertNull(LoginSyncInvite.parse("simpmusic://login-sync?h=192.168.1.5&p=51234&k=short"))
        assertNull(LoginSyncInvite.parse("simpmusic://login-sync?h=&p=51234&k=${Base64.UrlSafe.encode(ByteArray(32))}"))
    }

    @Test
    fun onlyTheSameKeyAndKindOpens() {
        val key = LoginSyncCipher.newKey()
        val aad = LoginSyncWire.aad(LoginSyncWire.PAYLOAD, reply = false)
        val sealed = LoginSyncCipher.seal(key, aad, "cookie".encodeToByteArray())

        assertEquals("cookie", LoginSyncCipher.open(key, aad, sealed)?.decodeToString())
        assertNull(LoginSyncCipher.open(LoginSyncCipher.newKey(), aad, sealed))
        // A hello replayed as a payload, or a request passed off as a reply.
        assertNull(LoginSyncCipher.open(key, LoginSyncWire.aad(LoginSyncWire.HELLO, reply = false), sealed))
        assertNull(LoginSyncCipher.open(key, LoginSyncWire.aad(LoginSyncWire.PAYLOAD, reply = true), sealed))
        val tampered = sealed.copyOf().also { it[it.lastIndex] = (it.last() + 1).toByte() }
        assertNull(LoginSyncCipher.open(key, aad, tampered))
    }

    /** The whole trip over a real socket: hello, payload, reply — then the spent code is refused. */
    @Test
    fun phoneAndComputerTalk() =
        runBlocking {
            val events = mutableListOf<String>()
            val server =
                LoginSyncServer(
                    object : LoginSyncServer.Handler {
                        override fun onHello() {
                            events += "hello"
                        }

                        override fun onCancel() {
                            events += "cancel"
                        }

                        override suspend fun onPayload(payload: ByteArray): ByteArray = ("got " + payload.decodeToString()).encodeToByteArray()

                        override fun onDelivered() {
                            events += "delivered"
                        }

                        override fun onRejected(reason: String) {
                            events += "rejected: $reason"
                        }
                    },
                )
            val session = server.start()
            try {
                val client = LoginSyncClient()
                val device = client.connect(session.invite("127.0.0.1"))
                assertTrue(device.name.isNotBlank())
                assertEquals("got sign-ins", client.send("sign-ins".encodeToByteArray()).decodeToString())
                assertEquals(listOf("hello", "delivered"), events)

                // A second phone with the same (now spent) code gets refused, not served.
                val refused = assertFailsWith<LoginSyncTransportException> { LoginSyncClient().connect(session.invite("127.0.0.1")) }
                assertEquals(LoginSyncTransportException.Kind.REFUSED, refused.kind)
            } finally {
                server.stop()
            }
        }
}
