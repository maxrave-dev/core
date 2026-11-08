package com.maxrave.data.browserlogin

import com.maxrave.domain.repository.BrowserLoginException
import com.maxrave.domain.repository.BrowserLoginFailure
import com.maxrave.domain.repository.BrowserLoginTarget
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.nio.file.Path

/** BinaryCookies has a big-endian file header and little-endian pages/records. */
internal object SafariCookieReader {
    fun read(source: Path, snapshots: BrowserCookieSnapshot, includeValues: Boolean, target: BrowserLoginTarget = BrowserLoginTarget.YOUTUBE): List<BrowserCookieRecord> =
        snapshots.readBytes(source) { parse(it, includeValues, target) }

    fun parse(data: ByteArray, includeValues: Boolean = true, target: BrowserLoginTarget = BrowserLoginTarget.YOUTUBE): List<BrowserCookieRecord> {
        try {
            require(data.size >= 8 && data.copyOfRange(0, 4).contentEquals(byteArrayOf(99, 111, 111, 107)))
            val header = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val count = header.getInt(4)
            require(count in 0..((data.size - 8) / 4))
            var pageOffset = 8L + count.toLong() * 4
            val records = mutableListOf<BrowserCookieRecord>()
            repeat(count) { pageIndex ->
                val pageSize = header.getInt(8 + pageIndex * 4)
                require(pageSize >= 8 && pageOffset + pageSize <= data.size.toLong())
                val page = ByteBuffer.wrap(data, pageOffset.toInt(), pageSize).slice().order(ByteOrder.LITTLE_ENDIAN)
                require(page.getInt(0) == 0x00010000)
                val cookies = page.getInt(4)
                require(cookies in 0..((pageSize - 8) / 4))
                repeat(cookies) cookie@{ cookieIndex ->
                    val offset = page.getInt(8 + cookieIndex * 4)
                    require(offset >= 8L + cookies.toLong() * 4 && offset <= pageSize - 56)
                    val length = page.getInt(offset)
                    require(length >= 56 && offset.toLong() + length <= pageSize)
                    val record = page.duplicate().apply { position(offset); limit(offset + length) }
                        .slice().order(ByteOrder.LITTLE_ENDIAN)
                    val host = readString(record, record.getInt(16))
                    if (BrowserCookieRecord.isTargetHost(host, target)) {
                        val name = readString(record, record.getInt(20))
                        if (!BrowserCookieRecord.isAllowedName(name, target)) return@cookie
                        val flags = record.getInt(8)
                        val expiry = record.getDouble(40)
                        require(expiry.isFinite() && expiry < Long.MAX_VALUE.toDouble() - MAC_EPOCH_SECONDS)
                        records += BrowserCookieRecord(
                            host = host,
                            name = name,
                            value = if (includeValues) readString(record, record.getInt(28)) else "",
                            path = readString(record, record.getInt(24)),
                            expiresSeconds = if (expiry == 0.0) 0 else expiry.toLong() + MAC_EPOCH_SECONDS,
                            secure = flags and 1 != 0,
                            httpOnly = flags and 4 != 0,
                        )
                    }
                }
                pageOffset += pageSize
            }
            return records
        } catch (error: BrowserLoginException) {
            throw error
        } catch (_: Exception) {
            throw BrowserLoginException(BrowserLoginFailure.INVALID_DATA)
        }
    }

    private fun readString(record: ByteBuffer, offset: Int): String {
        require(offset in 56 until record.limit())
        var end = offset
        while (end < record.limit() && record.get(end) != 0.toByte()) end++
        require(end < record.limit())
        val bytes = record.duplicate().apply { position(offset); limit(end) }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(bytes).toString()
    }

    private const val MAC_EPOCH_SECONDS = 978_307_200L
}
