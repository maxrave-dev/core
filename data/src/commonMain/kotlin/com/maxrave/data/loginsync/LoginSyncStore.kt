package com.maxrave.data.loginsync

import com.maxrave.data.db.datasource.LocalDataSource
import com.maxrave.data.io.fileDir
import com.maxrave.domain.data.entities.GoogleAccountEntity
import com.maxrave.domain.data.model.loginsync.LoginSyncService
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.manager.DataStoreManager.Values.TRUE
import com.maxrave.domain.repository.CommonRepository
import kotlinx.coroutines.flow.first

/** Reads this device's sign-ins, and writes a received bundle in the same shape the login screens do. */
internal class LoginSyncStore(
    private val dataStore: DataStoreManager,
    private val localDataSource: LocalDataSource,
    private val commonRepository: CommonRepository,
) {
    suspend fun signedIn(): List<LoginSyncService> =
        buildList {
            if (dataStore.loggedIn.first() == TRUE && dataStore.cookie.first().isNotEmpty()) add(LoginSyncService.YOUTUBE)
            if (dataStore.spdc.first().isNotEmpty()) add(LoginSyncService.SPOTIFY)
            if (dataStore.discordToken.first().isNotEmpty()) add(LoginSyncService.DISCORD)
            if (dataStore.lastfmSessionKey.first().isNotEmpty()) add(LoginSyncService.LASTFM)
        }

    suspend fun bundle(services: Set<LoginSyncService>): LoginBundle =
        LoginBundle(
            youtube =
                if (LoginSyncService.YOUTUBE in services) {
                    YouTubeLogin(
                        accounts = localDataSource.getGoogleAccounts().map { it.toWire() },
                        cookie = dataStore.cookie.first(),
                        pageId = dataStore.pageId.first().ifEmpty { null },
                        authUser = dataStore.authUser.first(),
                        dataSyncId = dataStore.dataSyncId.first(),
                    )
                } else {
                    null
                },
            spotifySpDc = if (LoginSyncService.SPOTIFY in services) dataStore.spdc.first() else null,
            discordToken = if (LoginSyncService.DISCORD in services) dataStore.discordToken.first() else null,
            lastfm =
                if (LoginSyncService.LASTFM in services) {
                    LastfmLogin(dataStore.lastfmSessionKey.first(), dataStore.lastfmUsername.first())
                } else {
                    null
                },
        )

    /** Writes [bundle] over this device's sign-ins and returns the services it carried. */
    suspend fun restore(bundle: LoginBundle): List<LoginSyncService> =
        buildList {
            bundle.youtube?.let {
                restoreYouTube(it)
                add(LoginSyncService.YOUTUBE)
            }
            bundle.spotifySpDc?.takeIf { it.isNotEmpty() }?.let { spDc ->
                dataStore.setSpdc(spDc)
                // A personal token minted for the previous sp_dc would otherwise stay in use until it
                // expires — the same reset the Spotify logout does.
                dataStore.setSpotifyPersonalToken("")
                dataStore.setSpotifyPersonalTokenExpires(0)
                add(LoginSyncService.SPOTIFY)
            }
            bundle.discordToken?.takeIf { it.isNotEmpty() }?.let {
                dataStore.setDiscordToken(it)
                add(LoginSyncService.DISCORD)
            }
            bundle.lastfm?.takeIf { it.sessionKey.isNotEmpty() }?.let {
                dataStore.setLastfmSession(sessionKey = it.sessionKey, username = it.username)
                add(LoginSyncService.LASTFM)
            }
        }

    /**
     * Mirrors SettingsViewModel.setUsedAccount. Order matters: the account rows go in BEFORE the
     * cookie, because CommonRepositoryImpl rewrites the yt-dlp cookie file from the used account the
     * moment the cookie changes — written the other way round it would write the old account's file.
     */
    private suspend fun restoreYouTube(login: YouTubeLogin) {
        localDataSource.getGoogleAccounts().forEach { localDataSource.updateGoogleAccountUsed(it.email, false) }
        login.accounts.forEach { localDataSource.insertGoogleAccount(it.toEntity()) }
        val used = login.accounts.firstOrNull { it.isUsed }
        dataStore.putString("AccountName", used?.name.orEmpty())
        dataStore.putString("AccountThumbUrl", used?.thumbnailUrl.orEmpty())
        used?.netscapeCookie?.let { commonRepository.writeTextToFile(it, "${fileDir()}/ytdlp-cookie.txt") }
        if (login.dataSyncId.isNotEmpty()) dataStore.setDataSyncId(login.dataSyncId)
        dataStore.setCookie(login.cookie, login.pageId, login.authUser)
        dataStore.setLoggedIn(true)
    }
}

private fun GoogleAccountEntity.toWire() = GoogleAccountWire(email, name, thumbnailUrl, pageId, authUser, cache, isUsed, netscapeCookie)

private fun GoogleAccountWire.toEntity() =
    GoogleAccountEntity(
        email = email,
        name = name,
        thumbnailUrl = thumbnailUrl,
        pageId = pageId,
        authUser = authUser,
        cache = cache,
        isUsed = isUsed,
        netscapeCookie = netscapeCookie,
    )
