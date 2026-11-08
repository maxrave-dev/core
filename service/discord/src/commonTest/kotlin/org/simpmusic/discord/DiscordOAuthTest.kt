package org.simpmusic.discord

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DiscordOAuthTest {
    // RFC 7636, Appendix B.
    @Test
    fun codeChallengeMatchesTheRfcExample() {
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            DiscordOAuth.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"),
        )
    }

    @Test
    fun authorizeUrlCarriesTheChallengeOfItsOwnVerifier() {
        configDiscord("1234")
        val login = DiscordOAuth.newLogin()

        assertTrue(login.authorizeUrl.startsWith("https://discord.com/oauth2/authorize?"))
        assertTrue("client_id=1234" in login.authorizeUrl)
        assertTrue("code_challenge=${DiscordOAuth.codeChallenge(login.codeVerifier)}" in login.authorizeUrl)
        assertTrue("state=${login.state}" in login.authorizeUrl)
        assertEquals(43, login.codeVerifier.length)
        assertNotEquals(login.codeVerifier, DiscordOAuth.newLogin().codeVerifier)
    }
}
