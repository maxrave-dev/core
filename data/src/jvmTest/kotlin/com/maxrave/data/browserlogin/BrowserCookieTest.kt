package com.maxrave.data.browserlogin

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import com.maxrave.domain.repository.BrowserLoginException
import com.maxrave.domain.repository.BrowserLoginFailure
import com.maxrave.domain.repository.BrowserLoginTarget
import com.maxrave.domain.repository.BrowserProfileStatus
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Synthetic cookies only. These tests never discover browsers or access an OS credential store. */
class BrowserCookieTest {
    private val key = ByteArray(16) { it.toByte() }

    @Test
    fun decryptsLegacyChromiumCookie() {
        val encrypted = encrypt("synthetic-value".toByteArray())
        assertEquals("synthetic-value", ChromiumCookieReader.decryptValue(encrypted, ".youtube.com", 23, key))
    }

    @Test
    fun verifiesChromiumDomainHashBeforeRemovingIt() {
        val hash = MessageDigest.getInstance("SHA-256").digest(".youtube.com".toByteArray())
        val encrypted = encrypt(hash + "synthetic-value".toByteArray())
        assertEquals("synthetic-value", ChromiumCookieReader.decryptValue(encrypted, ".youtube.com", 24, key))
        assertFailsWith<BrowserLoginException> {
            ChromiumCookieReader.decryptValue(encrypted, ".example.org", 24, key)
        }
    }

    @Test
    fun rejectsWrongKeyUnknownPrefixAndTruncatedCiphertext() {
        val encrypted = encrypt("synthetic-value".toByteArray())
        assertFailsWith<BrowserLoginException> {
            ChromiumCookieReader.decryptValue(encrypted, ".youtube.com", 23, ByteArray(16))
        }
        assertFailsWith<BrowserLoginException> {
            ChromiumCookieReader.decryptValue("v20unknown".toByteArray(), ".youtube.com", 24, key)
        }
        assertFailsWith<BrowserLoginException> {
            ChromiumCookieReader.decryptValue("v10short".toByteArray(), ".youtube.com", 24, key)
        }
    }

    @Test
    fun derivesLinuxLegacyKeyUsingChromiumParameters() {
        val derived = ChromiumCookieReader.deriveKey("peanuts".toByteArray(), 1)
        assertEquals("fd621fe5a2b402539dfa147ca9272778", derived.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun chromiumReaderUsesTheSelectedDomainAndNeverRequestsALinuxV10Key() = chromiumDatabase { source, copies ->
        val linuxKey = ChromiumCookieReader.deriveKey("peanuts".toByteArray(), 1)
        val hash = MessageDigest.getInstance("SHA-256").digest(".youtube.com".toByteArray())
        val encrypted = encrypt(hash + "synthetic-value".toByteArray(), linuxKey)
        insertChromiumCookie(source, ".youtube.com", "SAPISID", "", encrypted)
        insertChromiumCookie(source, ".example.org", "unrelated", "not-imported", byteArrayOf())
        assertEquals(BrowserProfileStatus.COOKIES_FOUND, ChromiumCookieReader.inspect(source, copies, macOS = false))
        val records = ChromiumCookieReader.read(source, copies, "unused", macOS = false) { _, _ ->
            error("A v10 fixture must not access any credential store")
        }
        assertEquals(1, records.size)
        assertEquals("synthetic-value", records.single().value)
        linuxKey.fill(0)
    }

    @Test
    fun chromiumDiscoveryDetectsUnsupportedEncryptionWithoutAskingForAKey() = chromiumDatabase { source, copies ->
        insertChromiumCookie(source, ".youtube.com", "SAPISID", "", "v20unsupported".toByteArray())
        assertEquals(BrowserProfileStatus.UNSUPPORTED, ChromiumCookieReader.inspect(source, copies, macOS = false))
    }

    @Test
    fun chromiumDoesNotRequestAKeyWhenOnlyPreferenceCookiesExist() = chromiumDatabase { source, copies ->
        insertChromiumCookie(source, ".youtube.com", "CONSENT", "", encrypt("synthetic".toByteArray()))
        val error = assertFailsWith<BrowserLoginException> {
            ChromiumCookieReader.read(source, copies, "unused", macOS = true) { _, _ ->
                error("Preference cookies must not cause an authorization prompt")
            }
        }
        assertEquals(BrowserLoginFailure.NO_COOKIES, error.reason)
    }

    @Test
    fun chromiumStopsAfterThePasswordLookupIsDenied() = chromiumDatabase { source, copies ->
        insertChromiumCookie(source, ".youtube.com", "SAPISID", "", encrypt("synthetic".toByteArray()))
        var lookups = 0
        val error = assertFailsWith<BrowserLoginException> {
            ChromiumCookieReader.read(source, copies, "unused", macOS = true) { _, _ ->
                lookups++
                throw BrowserLoginException(BrowserLoginFailure.ACCESS_DENIED)
            }
        }
        assertEquals(1, lookups)
        assertEquals(BrowserLoginFailure.ACCESS_DENIED, error.reason)
    }

    @Test
    fun chromiumOmitsThirdPartyPartitionCookies() = chromiumDatabase { source, copies ->
        insertChromiumCookie(source, ".youtube.com", "SAPISID", "synthetic", byteArrayOf())
        insertChromiumCookie(source, ".youtube.com", "partitioned", "other-context", byteArrayOf(), partition = "https://example.org")
        val records = ChromiumCookieReader.read(source, copies, "unused", macOS = false) { _, _ -> error("No encrypted values") }
        assertEquals(listOf("SAPISID"), records.map { it.name })
    }

    @Test
    fun spotifyReadsOnlySpDcAndDoesNotRequestTheYouTubeKey() = chromiumDatabase { source, copies ->
        insertChromiumCookie(source, ".spotify.com", "sp_dc", "synthetic-spotify", byteArrayOf())
        insertChromiumCookie(source, ".spotify.com", "sp_key", "not-imported", byteArrayOf())
        insertChromiumCookie(source, ".youtube.com", "SAPISID", "", "v20not-imported".toByteArray())
        assertEquals(BrowserProfileStatus.COOKIES_FOUND, ChromiumCookieReader.inspect(source, copies, false, BrowserLoginTarget.SPOTIFY))
        val records = ChromiumCookieReader.read(source, copies, "unused", false, BrowserLoginTarget.SPOTIFY) { _, _ ->
            error("Unrelated encrypted cookies must not cause a key lookup")
        }
        assertEquals(listOf("sp_dc"), records.map { it.name })
        assertEquals("sp_dc=synthetic-spotify", BrowserCookieRecord.toSpotifyCookies(records, 10).cookie)
    }

    @Test
    fun youtubeCookiesDoNotMarkASpotifyProfileSignedIn() = chromiumDatabase { source, copies ->
        insertChromiumCookie(source, ".youtube.com", "SAPISID", "synthetic", byteArrayOf())
        assertEquals(BrowserProfileStatus.NO_COOKIES, ChromiumCookieReader.inspect(source, copies, false, BrowserLoginTarget.SPOTIFY))
    }

    @Test
    fun spotifyExportContainsOnlyCurrentSpDcWithTheCorrectDomainScope() {
        val result = BrowserCookieRecord.toSpotifyCookies(
            listOf(
                record(".spotify.com", "sp_dc", "synthetic"),
                record(".spotify.com", "sp_key", "not-imported"),
                record(".evilspotify.com", "sp_dc", "lookalike"),
                record(".youtube.com", "SAPISID", "other-service"),
            ),
            nowSeconds = 10,
        )
        assertEquals("sp_dc=synthetic", result.cookie)
        assertFalse(result.netscapeCookie.contains("sp_key"))
        assertFalse(result.netscapeCookie.contains("lookalike"))
        assertFalse(result.netscapeCookie.contains("SAPISID"))
    }

    @Test
    fun spotifyRequiresSpDcRatherThanAnotherAuthenticationCookieName() {
        val error = assertFailsWith<BrowserLoginException> {
            BrowserCookieRecord.toSpotifyCookies(listOf(record(".spotify.com", "SID", "synthetic")), 10)
        }
        assertEquals(BrowserLoginFailure.NO_COOKIES, error.reason)
    }

    @Test
    fun selectsHeaderCookiesByHostPathAndExpiry() {
        val records = listOf(
            record(".youtube.com", "SAPISID", "domain"),
            record("youtube.com", "host-only", "exclude"),
            record("music.youtube.com", "music", "include"),
            record(".evilyoutube.com", "lookalike", "exclude"),
            record(".youtube.com", "expired", "exclude", expires = 9),
            record(".youtube.com", "other-path", "exclude", path = "/account"),
        )
        val result = BrowserCookieRecord.toYouTubeCookies(records, nowSeconds = 10)
        assertEquals("SAPISID=domain; music=include", result.cookie)
        assertFalse(result.netscapeCookie.contains("lookalike"))
        assertFalse(result.netscapeCookie.contains("expired"))
        assertTrue(result.netscapeCookie.contains("youtube.com\tFALSE\t/\tTRUE\t0\thost-only\texclude"))
    }

    @Test
    fun headerPrefersTheMostSpecificCookieWithTheSameName() {
        val result = BrowserCookieRecord.toYouTubeCookies(
            listOf(record(".youtube.com", "SAPISID", "broad"), record("music.youtube.com", "SAPISID", "specific")),
            nowSeconds = 10,
        )
        assertEquals("SAPISID=specific", result.cookie)
        assertTrue(result.netscapeCookie.contains("broad"))
    }

    @Test
    fun netscapePreservesHttpOnlySessionAndPersistentExpiry() {
        val result = BrowserCookieRecord.toYouTubeCookies(
            listOf(record(".youtube.com", "SAPISID", "fake", httpOnly = true, expires = 123)),
            nowSeconds = 10,
        )
        assertTrue(result.netscapeCookie.startsWith("# Netscape HTTP Cookie File\n"))
        assertTrue(result.netscapeCookie.contains("#HttpOnly_.youtube.com\tTRUE\t/\tTRUE\t123\tSAPISID\tfake"))
        assertFalse(result.toString().contains("fake"))
    }

    @Test
    fun rejectsHeaderAndNetscapeControlCharacters() {
        assertFailsWith<BrowserLoginException> {
            BrowserCookieRecord.toYouTubeCookies(listOf(record(".youtube.com", "SAPISID", "bad\r\nvalue")), 10)
        }
    }

    @Test
    fun geckoExpiryChangesUnitsAtSchema16() {
        assertEquals(1_800_000_000L, GeckoCookieReader.expirySeconds(1_800_000_000L, 15))
        assertEquals(1_800_000_000L, GeckoCookieReader.expirySeconds(1_800_000_000_000L, 16))
        assertEquals(0L, GeckoCookieReader.expirySeconds(0, 17))
    }

    @Test
    fun geckoDoesNotMixContainersOrThirdPartyPartitions() {
        assertTrue(GeckoCookieReader.isDefaultContext(""))
        assertTrue(GeckoCookieReader.isDefaultContext("^userContextId=0"))
        assertFalse(GeckoCookieReader.isDefaultContext("^userContextId=1"))
        assertFalse(GeckoCookieReader.isDefaultContext("^userContextId=10&partitionKey=%28https%2Cexample.org%29"))
        assertFalse(GeckoCookieReader.isDefaultContext("^partitionKey=%28https%2Cexample.org%29"))
    }

    @Test
    fun geckoReaderFiltersContainersAndConvertsActualDatabaseExpiry() {
        val root = Files.createTempDirectory("browser-cookie-fixture-")
        val source = root.resolve("cookies.sqlite")
        try {
            BundledSQLiteDriver().open(source.toString()).use { database ->
                database.prepare("PRAGMA user_version=16").use { it.step() }
                database.prepare("CREATE TABLE moz_cookies(host TEXT, name TEXT, value TEXT, path TEXT, expiry INTEGER, isSecure INTEGER, isHttpOnly INTEGER, originAttributes TEXT)").use { it.step() }
                database.prepare("INSERT INTO moz_cookies VALUES ('.youtube.com', 'SAPISID', 'synthetic', '/', 1800000000000, 1, 1, '')").use { it.step() }
                database.prepare("INSERT INTO moz_cookies VALUES ('.youtube.com', 'SID', 'other-container', '/', 1800000000000, 1, 1, '^userContextId=1')").use { it.step() }
                database.prepare("INSERT INTO moz_cookies VALUES ('.example.org', 'unrelated', 'other-domain', '/', 1800000000000, 1, 1, '')").use { it.step() }
            }
            val copies = BrowserCookieSnapshot(root.resolve("snapshots"))
            val records = GeckoCookieReader.read(source, copies, includeValues = true)
            assertEquals(1, records.size)
            assertEquals(1_800_000_000L, records.single().expiresSeconds)
            assertEquals("synthetic", records.single().value)
            assertTrue(GeckoCookieReader.read(source, copies, includeValues = false).all { it.value.isEmpty() })
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun geckoSpotifyImportReadsOnlyTheDefaultContainerSpDc() {
        val root = Files.createTempDirectory("browser-cookie-fixture-")
        val source = root.resolve("cookies.sqlite")
        try {
            BundledSQLiteDriver().open(source.toString()).use { database ->
                database.prepare("PRAGMA user_version=16").use { it.step() }
                database.prepare("CREATE TABLE moz_cookies(host TEXT, name TEXT, value TEXT, path TEXT, expiry INTEGER, isSecure INTEGER, isHttpOnly INTEGER, originAttributes TEXT)").use { it.step() }
                database.prepare("INSERT INTO moz_cookies VALUES ('.spotify.com', 'sp_dc', 'synthetic', '/', 1800000000000, 1, 1, '')").use { it.step() }
                database.prepare("INSERT INTO moz_cookies VALUES ('.spotify.com', 'sp_key', 'not-imported', '/', 1800000000000, 1, 1, '')").use { it.step() }
                database.prepare("INSERT INTO moz_cookies VALUES ('.youtube.com', 'SAPISID', 'other-service', '/', 1800000000000, 1, 1, '')").use { it.step() }
            }
            val records = GeckoCookieReader.read(source, BrowserCookieSnapshot(root.resolve("snapshots")), true, BrowserLoginTarget.SPOTIFY)
            assertEquals(listOf("sp_dc"), records.map { it.name })
            assertEquals("sp_dc=synthetic", BrowserCookieRecord.toSpotifyCookies(records, 10).cookie)
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun parsesSyntheticSafariCookiePage() {
        val records = SafariCookieReader.parse(safariFixture())
        assertEquals(1, records.size)
        assertEquals(".youtube.com", records.single().host)
        assertEquals("SAPISID", records.single().name)
        assertEquals("synthetic", records.single().value)
        assertEquals(1_978_307_200L, records.single().expiresSeconds)
        assertTrue(records.single().secure)
        assertTrue(records.single().httpOnly)
    }

    @Test
    fun safariSpotifyParsingDoesNotReturnAnotherService() {
        val fixture = safariFixture(host = ".spotify.com", name = "sp_dc")
        val records = SafariCookieReader.parse(fixture, target = BrowserLoginTarget.SPOTIFY)
        assertEquals("sp_dc", records.single().name)
        assertTrue(SafariCookieReader.parse(fixture, target = BrowserLoginTarget.YOUTUBE).isEmpty())
    }

    @Test
    fun rejectsMalformedSafariLengthsAndOffsets() {
        assertFailsWith<BrowserLoginException> { SafariCookieReader.parse("cook".toByteArray()) }
        val broken = safariFixture()
        ByteBuffer.wrap(broken).order(ByteOrder.LITTLE_ENDIAN).putInt(20, Int.MAX_VALUE)
        assertFailsWith<BrowserLoginException> { SafariCookieReader.parse(broken) }
    }

    @Test
    fun snapshotReadsCommittedWalWithoutChangingSource() {
        val root = Files.createTempDirectory("browser-cookie-fixture-")
        val source = root.resolve("cookies.sqlite")
        try {
            BundledSQLiteDriver().open(source.toString()).use { database ->
                database.prepare("PRAGMA journal_mode=WAL").use { it.step() }
                database.prepare("PRAGMA wal_autocheckpoint=0").use { it.step() }
                database.prepare("CREATE TABLE fixture(value TEXT)").use { it.step() }
                database.prepare("INSERT INTO fixture VALUES ('synthetic')").use { it.step() }
                val before = Files.readAllBytes(source)
                BrowserCookieSnapshot(root.resolve("snapshots")).read(source) { copy ->
                    copy.prepare("SELECT value FROM fixture").use {
                        assertTrue(it.step())
                        assertEquals("synthetic", it.getText(0))
                    }
                }
                assertTrue(before.contentEquals(Files.readAllBytes(source)))
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun snapshotCleansCopiesAfterCallbackFailure() {
        val root = Files.createTempDirectory("browser-cookie-fixture-")
        val copies = root.resolve("snapshots")
        val source = root.resolve("cookies.sqlite")
        try {
            BundledSQLiteDriver().open(source.toString()).use { database ->
                database.prepare("CREATE TABLE fixture(value TEXT)").use { it.step() }
            }
            assertFailsWith<IllegalStateException> {
                BrowserCookieSnapshot(copies).read(source) { error("synthetic failure") }
            }
            Files.list(copies).use { entries ->
                assertEquals(0L, entries.filter { it.fileName.toString().startsWith("import-") }.count())
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun snapshotUsesPrivateUnixPermissions() {
        val root = Files.createTempDirectory("browser-cookie-fixture-")
        val copies = root.resolve("snapshots")
        val source = root.resolve("cookies.sqlite")
        try {
            BundledSQLiteDriver().open(source.toString()).use { database ->
                database.prepare("CREATE TABLE fixture(value TEXT)").use { it.step() }
            }
            BrowserCookieSnapshot(copies).read(source) {
                if (Files.getFileStore(copies).supportsFileAttributeView("posix")) {
                    assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(copies)))
                    Files.list(copies).use { entries ->
                        val directory = entries.filter { it.fileName.toString().startsWith("import-") }.findFirst().orElseThrow()
                        assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(directory)))
                        assertEquals("rw-------", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.resolve("cookies.sqlite"))))
                    }
                }
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun chromiumDatabase(block: (java.nio.file.Path, BrowserCookieSnapshot) -> Unit) {
        val root = Files.createTempDirectory("browser-cookie-fixture-")
        val source = root.resolve("Cookies")
        try {
            BundledSQLiteDriver().open(source.toString()).use { database ->
                database.prepare("CREATE TABLE meta(key TEXT, value TEXT)").use { it.step() }
                database.prepare("INSERT INTO meta VALUES ('version', '24')").use { it.step() }
                database.prepare("CREATE TABLE cookies(host_key TEXT, name TEXT, path TEXT, expires_utc INTEGER, is_secure INTEGER, is_httponly INTEGER, value TEXT, encrypted_value BLOB, has_expires INTEGER, top_frame_site_key TEXT)").use { it.step() }
            }
            block(source, BrowserCookieSnapshot(root.resolve("snapshots")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun insertChromiumCookie(
        source: java.nio.file.Path,
        host: String,
        name: String,
        value: String,
        encrypted: ByteArray,
        partition: String = "",
    ) {
        BundledSQLiteDriver().open(source.toString()).use { database ->
            database.prepare("INSERT INTO cookies VALUES (?, ?, '/', 0, 1, 1, ?, ?, 0, ?)").use {
                it.bindText(1, host)
                it.bindText(2, name)
                it.bindText(3, value)
                it.bindBlob(4, encrypted)
                it.bindText(5, partition)
                it.step()
            }
        }
    }

    private fun encrypt(value: ByteArray, encryptionKey: ByteArray = key): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encryptionKey, "AES"), IvParameterSpec(ByteArray(16) { 32 }))
        return "v10".toByteArray() + cipher.doFinal(value)
    }

    private fun record(
        host: String,
        name: String,
        value: String,
        expires: Long = 0,
        path: String = "/",
        httpOnly: Boolean = false,
    ) = BrowserCookieRecord(host, name, value, path, expires, secure = true, httpOnly = httpOnly)

    private fun safariFixture(host: String = ".youtube.com", name: String = "SAPISID"): ByteArray {
        val strings = listOf(host, name, "/", "synthetic").map { (it + '\u0000').toByteArray() }
        val recordSize = 56 + strings.sumOf { it.size }
        val record = ByteBuffer.allocate(recordSize).order(ByteOrder.LITTLE_ENDIAN)
        record.putInt(recordSize).putInt(0).putInt(5).putInt(0)
        var offset = 56
        strings.forEach {
            record.putInt(offset)
            offset += it.size
        }
        record.putLong(0).putDouble(1_000_000_000.0).putDouble(900_000_000.0)
        strings.forEach { record.put(it) }
        val page = ByteBuffer.allocate(16 + recordSize).order(ByteOrder.LITTLE_ENDIAN)
        page.put(byteArrayOf(0, 0, 1, 0)).putInt(1).putInt(16).putInt(0).put(record.array())
        return ByteBuffer.allocate(12 + page.capacity()).order(ByteOrder.BIG_ENDIAN)
            .put("cook".toByteArray()).putInt(1).putInt(page.capacity()).put(page.array()).array()
    }
}
