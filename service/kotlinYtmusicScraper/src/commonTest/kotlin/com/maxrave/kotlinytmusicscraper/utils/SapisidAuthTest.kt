package com.maxrave.kotlinytmusicscraper.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SapisidAuthTest {
    @Test
    fun parseCookieStringHandlesCompactSemicolonsAndEqualsInsideValues() {
        val raw =
            "VISITOR_INFO1_LIVE=abc;__Secure-3PAPISID=3pSecret==; PREF=f6=40000000&tz=Asia.Saigon; ; invalid_segment"
        val parsed = parseCookieString(raw)

        assertEquals("abc", parsed["VISITOR_INFO1_LIVE"])
        assertEquals("3pSecret==", parsed["__Secure-3PAPISID"])
        assertEquals("f6=40000000&tz=Asia.Saigon", parsed["PREF"])
        assertNull(parsed["invalid_segment"])
    }

    @Test
    fun buildSapisidAuthorizationWorksWhenOnlySecure3pApisidExists() {
        val epochSeconds = 1_763_865_600L
        val cookies = mapOf("__Secure-3PAPISID" to "test_3p_sapisid")

        val header = buildSapisidAuthorization(cookies, epochSeconds)
        assertNotNull(header)
        val expectedDigest = sha1("$epochSeconds test_3p_sapisid https://music.youtube.com")
        assertEquals(
            "SAPISIDHASH ${epochSeconds}_$expectedDigest SAPISID3PHASH ${epochSeconds}_$expectedDigest",
            header,
        )
    }

    @Test
    fun buildSapisidAuthorizationIncludes1pAnd3pTokensWithFullEpochSeconds() {
        val epochSeconds = 1_763_865_600L
        val cookies =
            mapOf(
                "SAPISID" to "legacy_sapisid",
                "__Secure-1PAPISID" to "secure_1p",
                "__Secure-3PAPISID" to "secure_3p",
            )

        val header = assertNotNull(buildSapisidAuthorization(cookies, epochSeconds))
        val expected0 = sha1("$epochSeconds legacy_sapisid https://music.youtube.com")
        val expected1p = sha1("$epochSeconds secure_1p https://music.youtube.com")
        val expected3p = sha1("$epochSeconds secure_3p https://music.youtube.com")
        assertEquals(
            "SAPISIDHASH ${epochSeconds}_$expected0 SAPISID1PHASH ${epochSeconds}_$expected1p SAPISID3PHASH ${epochSeconds}_$expected3p",
            header,
        )
    }

    @Test
    fun buildSapisidAuthorizationReturnsNullWhenNoSapisidCookiesExist() {
        val cookies = mapOf("VISITOR_INFO1_LIVE" to "abc", "PREF" to "tz=UTC")
        assertNull(buildSapisidAuthorization(cookies, epochSeconds = 1_763_865_600L))
    }
}
