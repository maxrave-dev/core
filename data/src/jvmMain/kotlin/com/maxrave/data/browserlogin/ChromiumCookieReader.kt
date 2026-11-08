package com.maxrave.data.browserlogin

import com.maxrave.domain.repository.BrowserLoginException
import com.maxrave.domain.repository.BrowserLoginFailure
import com.maxrave.domain.repository.BrowserLoginTarget
import com.maxrave.domain.repository.BrowserProfileStatus
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Path
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object ChromiumCookieReader {
    fun inspect(source: Path, snapshots: BrowserCookieSnapshot, macOS: Boolean, target: BrowserLoginTarget = BrowserLoginTarget.YOUTUBE): BrowserProfileStatus {
        val rows = rows(source, snapshots, includeValues = false, target = target).second
        val now = System.currentTimeMillis() / 1_000
        val authentication = rows.filter {
            BrowserCookieRecord.isAuthenticationName(it.name, target) && it.asRecord("").matchesRequestHost(target) &&
                it.path == "/" && (it.expiry == 0L || it.expiry > now)
        }
        return when {
            authentication.isEmpty() -> BrowserProfileStatus.NO_COOKIES
            authentication.any { !it.plaintext && !supportedPrefix(it.encrypted, macOS) } -> BrowserProfileStatus.UNSUPPORTED
            else -> BrowserProfileStatus.COOKIES_FOUND
        }
    }

    fun read(
        source: Path,
        snapshots: BrowserCookieSnapshot,
        keyringName: String,
        macOS: Boolean,
        target: BrowserLoginTarget = BrowserLoginTarget.YOUTUBE,
        passwordLookup: (String, Boolean) -> ByteArray = SafeStorageKey::password,
    ): List<BrowserCookieRecord> {
        val (version, rows) = rows(source, snapshots, includeValues = true, target = target)
        val keys = mutableMapOf<String, ByteArray>()
        try {
            val now = System.currentTimeMillis() / 1_000
            if (rows.none {
                    BrowserCookieRecord.isAuthenticationName(it.name, target) && it.path == "/" &&
                        it.asRecord("").matchesRequestHost(target) && (it.expiry == 0L || it.expiry > now)
                }
            ) {
                throw BrowserLoginException(BrowserLoginFailure.NO_COOKIES)
            }
            return rows.filter { it.expiry == 0L || it.expiry > System.currentTimeMillis() / 1_000 }.map { row ->
                val value = if (row.plaintext) {
                    row.value
                } else {
                    if (!supportedPrefix(row.encrypted, macOS)) {
                        throw BrowserLoginException(BrowserLoginFailure.UNSUPPORTED_ENCRYPTION)
                    }
                    val prefix = String(row.encrypted, 0, 3, Charsets.US_ASCII)
                    val key = keys.getOrPut(prefix) {
                        val password = if (!macOS && prefix == "v10") {
                            "peanuts".toByteArray(Charsets.US_ASCII)
                        } else {
                            passwordLookup(keyringName, macOS)
                        }
                        try {
                            deriveKey(password, if (macOS) 1_003 else 1)
                        } finally {
                            password.fill(0)
                        }
                    }
                    try {
                        decryptValue(row.encrypted, row.host, version, key)
                    } catch (error: BrowserLoginException) {
                        // Chromium historically tried the empty password for Linux data. Never
                        // fall back after a key-store denial, which occurs before this block.
                        if (macOS || error.reason == BrowserLoginFailure.UNSUPPORTED_ENCRYPTION) throw error
                        val emptyKey = deriveKey(byteArrayOf(), 1)
                        try {
                            decryptValue(row.encrypted, row.host, version, emptyKey)
                        } finally {
                            emptyKey.fill(0)
                        }
                    }
                }
                row.asRecord(value)
            }
        } finally {
            keys.values.forEach { it.fill(0) }
            rows.forEach { it.encrypted.fill(0) }
        }
    }

    private fun rows(source: Path, snapshots: BrowserCookieSnapshot, includeValues: Boolean, target: BrowserLoginTarget): Pair<Int, List<Row>> =
        snapshots.read(source) { database ->
            val version = database.prepare("SELECT value FROM meta WHERE key = 'version'").use {
                if (it.step()) it.getText(0).toIntOrNull() ?: 0 else 0
            }
            val columns = mutableSetOf<String>()
            database.prepare("PRAGMA table_info(cookies)").use { while (it.step()) columns += it.getText(1) }
            val secure = if ("is_secure" in columns) "is_secure" else "secure"
            val persistent = if ("has_expires" in columns) "has_expires" else "expires_utc != 0"
            val partition = if ("top_frame_site_key" in columns) " AND (top_frame_site_key = '' OR top_frame_site_key IS NULL)" else ""
            val value = if (includeValues) "value" else "''"
            val encrypted = if (includeValues) "encrypted_value" else "substr(encrypted_value, 1, 3)"
            val nameFilter = if (target == BrowserLoginTarget.SPOTIFY) " AND name = 'sp_dc'" else ""
            val domain = BrowserCookieRecord.domain(target)
            val records = mutableListOf<Row>()
            database.prepare(
                "SELECT host_key, name, path, expires_utc, $secure, is_httponly, $value, $encrypted, length(value) > 0, $persistent " +
                    "FROM cookies WHERE (host_key = ? OR host_key = ? OR host_key LIKE ?)$partition$nameFilter",
            ).use { statement ->
                statement.bindText(1, domain)
                statement.bindText(2, ".$domain")
                statement.bindText(3, "%.$domain")
                while (statement.step()) {
                    val host = statement.getText(0)
                    if (BrowserCookieRecord.isTargetHost(host, target) && BrowserCookieRecord.isAllowedName(statement.getText(1), target)) {
                        val rawExpiry = statement.getLong(3)
                        val encryptedBytes = if (statement.isNull(7)) byteArrayOf() else statement.getBlob(7)
                        records += Row(
                            host = host,
                            name = statement.getText(1),
                            path = statement.getText(2),
                            expiry = if (statement.getLong(9) == 0L || rawExpiry == 0L) 0 else rawExpiry / 1_000_000 - CHROME_EPOCH_SECONDS,
                            secure = statement.getLong(4) != 0L,
                            httpOnly = statement.getLong(5) != 0L,
                            value = if (statement.isNull(6)) "" else statement.getText(6),
                            encrypted = encryptedBytes,
                            plaintext = statement.getLong(8) != 0L || encryptedBytes.isEmpty(),
                        )
                    }
                }
            }
            version to records
        }

    fun decryptValue(encrypted: ByteArray, host: String, databaseVersion: Int, key: ByteArray): String {
        if (encrypted.size < 3 || !supportedPrefix(encrypted, macOS = false)) {
            throw BrowserLoginException(BrowserLoginFailure.UNSUPPORTED_ENCRYPTION)
        }
        var plaintext: ByteArray? = null
        try {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ByteArray(16) { 32 }))
            plaintext = cipher.doFinal(encrypted, 3, encrypted.size - 3)
            val offset = if (databaseVersion >= 24) {
                val hash = MessageDigest.getInstance("SHA-256").digest(host.toByteArray(Charsets.UTF_8))
                if (plaintext.size < hash.size || !MessageDigest.isEqual(hash, plaintext.copyOfRange(0, hash.size))) {
                    throw BrowserLoginException(BrowserLoginFailure.INVALID_DATA)
                }
                hash.size
            } else {
                0
            }
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(plaintext, offset, plaintext.size - offset)).toString()
        } catch (_: GeneralSecurityException) {
            throw BrowserLoginException(BrowserLoginFailure.KEY_UNAVAILABLE)
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw BrowserLoginException(BrowserLoginFailure.INVALID_DATA)
        } finally {
            plaintext?.fill(0)
        }
    }

    /** PBKDF2-HMAC-SHA1, 16 bytes, Chromium's saltysalt; input bytes never become a password String. */
    fun deriveKey(password: ByteArray, iterations: Int): ByteArray {
        require(iterations > 0)
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(if (password.isEmpty()) byteArrayOf(0) else password, "HmacSHA1"))
        var round = mac.doFinal("saltysalt".toByteArray(Charsets.US_ASCII) + byteArrayOf(0, 0, 0, 1))
        val result = round.copyOf()
        try {
            repeat(iterations - 1) {
                val next = mac.doFinal(round)
                round.fill(0)
                round = next
                result.indices.forEach { index -> result[index] = (result[index].toInt() xor round[index].toInt()).toByte() }
            }
            return result.copyOf(16)
        } finally {
            round.fill(0)
            result.fill(0)
        }
    }

    private fun supportedPrefix(value: ByteArray, macOS: Boolean): Boolean = value.size >= 3 &&
        value[0] == 'v'.code.toByte() && value[1] == '1'.code.toByte() &&
        (value[2] == '0'.code.toByte() || !macOS && value[2] == '1'.code.toByte())

    private class Row(
        val host: String,
        val name: String,
        val path: String,
        val expiry: Long,
        val secure: Boolean,
        val httpOnly: Boolean,
        val value: String,
        val encrypted: ByteArray,
        val plaintext: Boolean,
    ) {
        fun asRecord(value: String) = BrowserCookieRecord(host, name, value, path, expiry, secure, httpOnly)
    }

    private const val CHROME_EPOCH_SECONDS = 11_644_473_600L
}
