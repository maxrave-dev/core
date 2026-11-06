package com.maxrave.data.repository

import com.maxrave.domain.data.model.discord.DiscordSession
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.repository.DiscordRepository
import com.maxrave.logger.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.simpmusic.discord.DiscordAuthException
import org.simpmusic.discord.DiscordLoginRequest
import org.simpmusic.discord.DiscordOAuth
import org.simpmusic.discord.DiscordTokens
import org.simpmusic.discord.isDiscordOAuthAvailable
import kotlin.concurrent.Volatile
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.ExperimentalTime

/**
 * Which Discord login a build uses is decided by whether it carries a Discord application id
 * ([isDiscordOAuthAvailable]), which only Full builds do. Startup sets it before Koin creates this.
 */
internal class DiscordRepositoryImpl(
    private val dataStoreManager: DataStoreManager,
) : DiscordRepository {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // One refresh at a time: two callers refreshing together would each spend the same refresh token.
    private val refreshMutex = Mutex()

    // The attempt waiting for Discord's redirect. Memory only: a login the process did not survive
    // simply fails, and the user taps the button again.
    @Volatile private var pendingLogin: DiscordLoginRequest? = null

    override val isOAuth: Boolean = isDiscordOAuthAvailable()

    init {
        if (isOAuth) {
            // The token kizzy's WebView login stored is a full-account token this build no longer uses.
            scope.launch {
                if (dataStoreManager.discordToken.first().isNotEmpty()) {
                    dataStoreManager.setDiscordToken("")
                }
            }
        }
    }

    override val loggedIn: Flow<Boolean> =
        if (isOAuth) {
            dataStoreManager.discordSession.map { it != null }
        } else {
            dataStoreManager.discordToken.map { it.isNotBlank() }
        }

    override fun startLogin(): String? {
        if (!isOAuth) return null
        return DiscordOAuth.newLogin().also { pendingLogin = it }.authorizeUrl
    }

    override suspend fun completeLogin(callbackUrl: String): Boolean {
        val request = pendingLogin
        if (request == null) {
            Logger.w("Auth", "Discord: callback without a login in progress")
            return false
        }
        return try {
            saveTokens(DiscordOAuth.completeLogin(callbackUrl, request))
            pendingLogin = null
            Logger.i("Auth", "Discord: logged in through OAuth")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Logger.w("Auth", "Discord: login failed: ${e.message}")
            false
        }
    }

    @OptIn(ExperimentalTime::class)
    override suspend fun accessToken(forceRefresh: Boolean): String? {
        if (!isOAuth) return dataStoreManager.discordToken.first().ifBlank { null }
        return refreshMutex.withLock {
            val session = dataStoreManager.discordSession.first() ?: return@withLock null
            val now = Clock.System.now().toEpochMilliseconds()
            if (!forceRefresh && session.expiresAtMs - now > REFRESH_MARGIN.inWholeMilliseconds) {
                return@withLock session.accessToken
            }
            try {
                saveTokens(DiscordOAuth.refresh(session.refreshToken)).accessToken
            } catch (e: CancellationException) {
                throw e
            } catch (e: DiscordAuthException) {
                if (e.invalidGrant) {
                    // The user removed SimpMusic from Discord, or the refresh token expired.
                    Logger.w("Auth", "Discord: refresh token rejected, logging out")
                    dataStoreManager.setDiscordSession(null)
                    null
                } else {
                    Logger.w("Auth", "Discord: refreshing the token failed: ${e.message}")
                    session.accessToken.takeIf { session.expiresAtMs > now }
                }
            } catch (e: Exception) {
                Logger.w("Auth", "Discord: refreshing the token failed: ${e.message}")
                session.accessToken.takeIf { session.expiresAtMs > now }
            }
        }
    }

    override suspend fun logout() {
        pendingLogin = null
        if (isOAuth) {
            dataStoreManager.setDiscordSession(null)
        } else {
            dataStoreManager.setDiscordToken("")
        }
    }

    @OptIn(ExperimentalTime::class)
    private suspend fun saveTokens(tokens: DiscordTokens): DiscordSession {
        val session =
            DiscordSession(
                accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken,
                expiresAtMs = Clock.System.now().toEpochMilliseconds() + tokens.expiresInSeconds * 1000,
            )
        dataStoreManager.setDiscordSession(session)
        return session
    }

    private companion object {
        val REFRESH_MARGIN = 10.minutes
    }
}
