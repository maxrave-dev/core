package org.simpmusic.loginsync

import kotlinx.coroutines.runBlocking
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PairedSyncTest {
    @Test
    fun pairingCodeSurvivesTheQr() {
        val key = newPairingKey()
        val parsed = assertNotNull(SyncPairInvite.parse(SyncPairInvite(listOf("100.64.0.7"), PairedSyncWire.PORT, key).toUri()))
        assertEquals(listOf("100.64.0.7"), parsed.hosts)
        assertEquals(PairedSyncWire.PORT, parsed.port)
        assertContentEquals(key, parsed.key)
        assertContentEquals(key, assertNotNull(key.toKeyHex().keyFromHex()))
    }

    @Test
    fun loginCodesAndJunkAreNotPairingCodes() {
        val key = Base64.UrlSafe.encode(ByteArray(32))
        assertNull(SyncPairInvite.parse("simpmusic://login-sync?h=192.168.1.5&p=51234&k=$key"))
        assertNull(SyncPairInvite.parse("simpmusic://sync-pair?h=192.168.1.5&p=0&k=$key"))
        assertNull(SyncPairInvite.parse("simpmusic://sync-pair?h=192.168.1.5&p=48517&k=short"))
    }

    @Test
    fun aSealedBodyBelongsToOneKindOneDirectionAndOnePhone() {
        val key = newPairingKey()
        val sealed = LoginSyncCipher.seal(key, PairedSyncWire.aad(PairedSyncWire.SYNC, false, "phone-a"), "x".encodeToByteArray())
        assertNotNull(LoginSyncCipher.open(key, PairedSyncWire.aad(PairedSyncWire.SYNC, false, "phone-a"), sealed))
        assertNull(LoginSyncCipher.open(key, PairedSyncWire.aad(PairedSyncWire.SYNC, false, "phone-b"), sealed))
        assertNull(LoginSyncCipher.open(key, PairedSyncWire.aad(PairedSyncWire.SYNC, true, "phone-a"), sealed))
        assertNull(LoginSyncCipher.open(key, PairedSyncWire.aad(PairedSyncWire.UNPAIR, false, "phone-a"), sealed))
    }

    @Test
    fun gzipRoundTrips() {
        val plain = "liked songs ".repeat(1000).encodeToByteArray()
        assertContentEquals(plain, PairedSyncWire.gunzip(PairedSyncWire.gzip(plain)))
    }

    /**
     * Over a real socket on the fixed port: pair, find the computer, sync, then get removed. Fails with
     * a BindException while a SimpMusic Desktop with a paired phone is running on the same machine.
     */
    @Test
    fun phoneAndComputerStayPaired() =
        runBlocking {
            val pairingKey = newPairingKey()
            var phoneKey: ByteArray? = null
            var removed = false
            var told = false
            val server =
                PairedSyncServer(
                    object : PairedSyncServer.Handler {
                        override fun desktopId() = "desktop-1"

                        override fun pairingKey() = if (phoneKey == null) pairingKey else null

                        override suspend fun onPair(phone: PhoneHello): ByteArray = newPairingKey().also { phoneKey = it }

                        override fun keyFor(phoneId: String) = phoneKey.takeIf { phoneId == "phone-1" && !removed }

                        override fun revokedKeyFor(phoneId: String) = phoneKey.takeIf { phoneId == "phone-1" && removed }

                        override suspend fun onRevokedTold(phoneId: String) {
                            told = true
                        }

                        override suspend fun onSync(
                            phoneId: String,
                            request: ByteArray,
                        ) = ("got " + request.decodeToString()).encodeToByteArray()

                        override suspend fun onUnpair(phoneId: String) = Unit

                        override suspend fun onLogins(
                            phoneId: String,
                            payload: ByteArray,
                        ) = payload
                    },
                )
            server.start()
            try {
                val client = PairedSyncClient()
                val invite = SyncPairInvite(listOf("127.0.0.1"), PairedSyncWire.PORT, pairingKey)
                val phone = PhoneHello("phone-1", "Test phone", "Android")
                val (host, desktop) = client.pairHello(invite, phone)
                assertEquals("desktop-1", desktop.desktopId)
                val grant = client.pair(invite, host, phone)
                assertContentEquals(phoneKey, grant.keyHex.keyFromHex())

                // The QR is spent once a phone paired.
                assertFailsWith<PairedSyncException> { client.pair(invite, host, phone) }

                val target = PairedTarget("desktop-1", "phone-1", grant.keyHex.keyFromHex()!!, grant.port, "127.0.0.1", listOf("127.0.0.1"))
                val at = assertNotNull(client.locate(target))
                assertFalse(at.revoked)
                assertEquals("got changes", client.sync(target, at, "changes".encodeToByteArray()).decodeToString())

                // Someone holding a different key is not the paired phone.
                val stranger = PairedTarget("desktop-1", "phone-1", newPairingKey(), grant.port, "127.0.0.1", listOf("127.0.0.1"))
                val refused = assertFailsWith<PairedSyncException> { client.sync(stranger, at, "changes".encodeToByteArray()) }
                assertEquals(PairedSyncException.Kind.REFUSED, refused.kind)

                // Removed on the computer: the phone hears it, under its own key, the next time it knocks.
                removed = true
                assertTrue(assertNotNull(client.locate(target)).revoked)
                assertTrue(told)
            } finally {
                server.stop()
            }
        }
}
