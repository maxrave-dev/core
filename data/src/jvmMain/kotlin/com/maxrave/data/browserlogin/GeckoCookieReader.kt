package com.maxrave.data.browserlogin

import com.maxrave.domain.repository.BrowserLoginTarget
import java.nio.file.Path

internal object GeckoCookieReader {
    fun read(source: Path, snapshots: BrowserCookieSnapshot, includeValues: Boolean, target: BrowserLoginTarget = BrowserLoginTarget.YOUTUBE): List<BrowserCookieRecord> =
        snapshots.read(source) { database ->
            val schema = database.prepare("PRAGMA user_version").use { if (it.step()) it.getLong(0).toInt() else 0 }
            val columns = mutableSetOf<String>()
            database.prepare("PRAGMA table_info(moz_cookies)").use { while (it.step()) columns += it.getText(1) }
            val attributes = if ("originAttributes" in columns) "originAttributes" else "''"
            // Do not materialize plaintext values during discovery, even for the selected domain.
            val valueColumn = if (includeValues) "value" else "''"
            val nameFilter = if (target == BrowserLoginTarget.SPOTIFY) " AND name = 'sp_dc'" else ""
            val domain = BrowserCookieRecord.domain(target)
            val records = mutableListOf<BrowserCookieRecord>()
            database.prepare(
                "SELECT host, name, $valueColumn, path, expiry, isSecure, isHttpOnly, $attributes " +
                    "FROM moz_cookies WHERE (host = ? OR host = ? OR host LIKE ?)$nameFilter",
            ).use { statement ->
                statement.bindText(1, domain)
                statement.bindText(2, ".$domain")
                statement.bindText(3, "%.$domain")
                while (statement.step()) {
                    val host = statement.getText(0)
                    if (BrowserCookieRecord.isTargetHost(host, target) && BrowserCookieRecord.isAllowedName(statement.getText(1), target) && isDefaultContext(statement.getText(7))) {
                        records += BrowserCookieRecord(
                            host = host,
                            name = statement.getText(1),
                            value = statement.getText(2),
                            path = statement.getText(3),
                            expiresSeconds = expirySeconds(statement.getLong(4), schema),
                            secure = statement.getLong(5) != 0L,
                            httpOnly = statement.getLong(6) != 0L,
                        )
                    }
                }
            }
            records
        }

    // Firefox 142 / schema 16 switched expiry from seconds to milliseconds (Mozilla 5869af852c).
    fun expirySeconds(expiry: Long, schema: Int): Long = if (schema >= 16) expiry / 1_000 else expiry

    fun isDefaultContext(attributes: String): Boolean {
        val fields = attributes.removePrefix("^").split('&').filter { it.isNotEmpty() }
        return fields.all {
            val key = it.substringBefore('=')
            val value = it.substringAfter('=', "")
            when (key) {
                "userContextId", "privateBrowsingId" -> value == "0"
                "partitionKey", "firstPartyDomain" -> value.isEmpty()
                else -> false // Do not merge an unknown isolation context into the default session.
            }
        }
    }
}
