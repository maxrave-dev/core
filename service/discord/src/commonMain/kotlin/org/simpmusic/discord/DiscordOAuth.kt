package org.simpmusic.discord

import com.maxrave.ktorext.getEngine
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.statement.bodyAsText
import io.ktor.http.Parameters
import io.ktor.http.URLBuilder
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import io.ktor.http.parseQueryString
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** One login attempt: the page to open, and what has to survive until the user comes back. */
class DiscordLoginRequest internal constructor(
    val authorizeUrl: String,
    internal val state: String,
    internal val codeVerifier: String,
)

data class DiscordTokens(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
)

/** [invalidGrant] means Discord refused the code or the refresh token itself, so retrying cannot help. */
class DiscordAuthException(
    val invalidGrant: Boolean,
    message: String,
) : Exception(message)

/**
 * Discord's authorization code flow with PKCE, as a public client: the app holds no client secret,
 * and the code verifier is what proves that the app finishing the login is the one that started it.
 * The scopes are the Social SDK's presence set, which is what the gateway accepts a Bearer token for.
 */
object DiscordOAuth {
    const val REDIRECT_URI = "simpmusic://discord-auth"

    private const val SCOPES = "openid sdk.social_layer_presence"
    private const val AUTHORIZE_URL = "https://discord.com/oauth2/authorize"
    private const val TOKEN_URL = "https://discord.com/api/v10/oauth2/token"

    private val json = Json { ignoreUnknownKeys = true }

    private val client by lazy {
        HttpClient(getEngine()) {
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
            }
            expectSuccess = false
        }
    }

    fun newLogin(): DiscordLoginRequest {
        val verifier = randomToken()
        val state = randomToken()
        val url =
            URLBuilder(AUTHORIZE_URL)
                .apply {
                    parameters.append("client_id", discordClientId())
                    parameters.append("response_type", "code")
                    parameters.append("redirect_uri", REDIRECT_URI)
                    parameters.append("scope", SCOPES)
                    parameters.append("state", state)
                    parameters.append("code_challenge_method", "S256")
                    parameters.append("code_challenge", codeChallenge(verifier))
                }.buildString()
        return DiscordLoginRequest(authorizeUrl = url, state = state, codeVerifier = verifier)
    }

    /**
     * Finishes [request] from Discord's redirect, `simpmusic://discord-auth?code=…&state=…`, or the
     * same link pasted by hand. The state must match the one this attempt sent.
     */
    suspend fun completeLogin(
        callbackUrl: String,
        request: DiscordLoginRequest,
    ): DiscordTokens {
        val query = parseQueryString(callbackUrl.trim().substringAfter('?', ""))
        query["error"]?.let { throw DiscordAuthException(invalidGrant = true, message = "Authorization refused: $it") }
        val code = query["code"]
        if (code.isNullOrEmpty() || query["state"] != request.state) {
            throw DiscordAuthException(invalidGrant = true, message = "Callback without a code, or for another attempt")
        }
        return exchangeCode(code, request)
    }

    private suspend fun exchangeCode(
        code: String,
        request: DiscordLoginRequest,
    ): DiscordTokens =
        requestTokens(
            parameters {
                append("grant_type", "authorization_code")
                append("code", code)
                append("redirect_uri", REDIRECT_URI)
                append("code_verifier", request.codeVerifier)
            },
        )

    suspend fun refresh(refreshToken: String): DiscordTokens =
        requestTokens(
            parameters {
                append("grant_type", "refresh_token")
                append("refresh_token", refreshToken)
            },
        )

    private suspend fun requestTokens(grant: Parameters): DiscordTokens {
        val response =
            client.submitForm(
                url = TOKEN_URL,
                formParameters =
                    parameters {
                        append("client_id", discordClientId())
                        appendAll(grant)
                    },
            )
        val body = response.bodyAsText()
        if (response.status.isSuccess()) {
            val tokens = json.decodeFromString<TokenResponse>(body)
            return DiscordTokens(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                expiresInSeconds = tokens.expiresIn,
            )
        }
        val error = runCatching { json.decodeFromString<ErrorResponse>(body).error }.getOrNull()
        throw DiscordAuthException(
            invalidGrant = error == "invalid_grant",
            message = "Token request failed: HTTP ${response.status.value} ${error.orEmpty()}",
        )
    }

    /** RFC 7636 S256: BASE64URL(SHA256(ASCII(code_verifier))), without padding. */
    internal fun codeChallenge(verifier: String): String = verifier.encodeUtf8().sha256().base64Url().trimEnd('=')

    // Uuid.random() reads the platform CSPRNG (SecureRandom on the JVM, arc4random_buf on Apple).
    // Two version-4 uuids are 32 bytes holding 244 random bits; base64url gives 43 characters, the
    // shortest verifier RFC 7636 allows.
    @OptIn(ExperimentalUuidApi::class)
    private fun randomToken(): String =
        (Uuid.random().toByteArray() + Uuid.random().toByteArray())
            .toByteString()
            .base64Url()
            .trimEnd('=')

    @Serializable
    private data class TokenResponse(
        @SerialName("access_token") val accessToken: String,
        @SerialName("refresh_token") val refreshToken: String,
        @SerialName("expires_in") val expiresIn: Long,
    )

    @Serializable
    private data class ErrorResponse(
        @SerialName("error") val error: String? = null,
    )
}
