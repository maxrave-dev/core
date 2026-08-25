package com.maxrave.domain.manager

import com.maxrave.domain.data.model.network.ProxyConfiguration
import kotlinx.coroutines.flow.Flow

interface DataStoreManager {
    val appVersion: Flow<String>

    suspend fun setAppVersion(version: String)

    val openAppTime: Flow<Int>

    suspend fun openApp()

    suspend fun resetOpenAppTime()

    suspend fun doneOpenAppTime()

    val location: Flow<String>

    suspend fun setLocation(location: String)

    val quality: Flow<String>

    suspend fun setQuality(quality: String)

    val downloadQuality: Flow<String>

    suspend fun setDownloadQuality(quality: String)

    val videoDownloadQuality: Flow<String>

    suspend fun setVideoDownloadQuality(quality: String)

    val language: Flow<String>

    /**
     * Serialized "Moods & Genres" browse result, so the search and home screens can paint their
     * category grid immediately instead of waiting on the network every time. Null until the
     * first successful fetch.
     */
    val moodAndGenresCache: Flow<String?>

    suspend fun setMoodAndGenresCache(json: String)

    /**
     * Cover art resolved per browse category, keyed by its params. The category list itself
     * carries no artwork, so each cover costs one full category browse — worth remembering on
     * disk rather than paying again every time the search screen opens.
     */
    val moodArtworkCache: Flow<String?>

    suspend fun setMoodArtworkCache(json: String)

    fun getString(key: String): Flow<String?>

    suspend fun putString(
        key: String,
        value: String,
    )

    val loggedIn: Flow<String>
    val cookie: Flow<String>
    val pageId: Flow<String>

    suspend fun setCookie(
        cookie: String,
        pageId: String?,
    )

    suspend fun setLoggedIn(logged: Boolean)

    val normalizeVolume: Flow<String>

    suspend fun setNormalizeVolume(normalize: Boolean)

    val skipSilent: Flow<String>

    suspend fun setSkipSilent(skip: Boolean)

    val saveStateOfPlayback: Flow<String>

    suspend fun setSaveStateOfPlayback(save: Boolean)

    val shuffleKey: Flow<String>
    val repeatKey: Flow<String>

    suspend fun recoverShuffleAndRepeatKey(
        shuffle: Boolean,
        repeat: Int,
    )

    val saveRecentSongAndQueue: Flow<String>

    suspend fun setSaveRecentSongAndQueue(save: Boolean)

    val recentMediaId: Flow<String>
    val recentPosition: Flow<String>

    suspend fun saveRecentSong(
        mediaId: String,
        position: Long,
    )

    val playlistFromSaved: Flow<String>

    suspend fun setPlaylistFromSaved(playlist: String)

    val sendBackToGoogle: Flow<String>

    suspend fun setSendBackToGoogle(send: Boolean)

    val sponsorBlockEnabled: Flow<String>

    suspend fun setSponsorBlockEnabled(enabled: Boolean)

    suspend fun getSponsorBlockCategories(): ArrayList<String>

    suspend fun setSponsorBlockCategories(categories: ArrayList<String>)

    val enableTranslateLyric: Flow<String>

    suspend fun setEnableTranslateLyric(enable: Boolean)

    val lyricsProvider: Flow<String>

    suspend fun setLyricsProvider(provider: String)

    val translationLanguage: Flow<String>

    suspend fun setTranslationLanguage(language: String)

    val maxSongCacheSize: Flow<Int>

    suspend fun setMaxSongCacheSize(size: Int)

    val watchVideoInsteadOfPlayingAudio: Flow<String>

    suspend fun setWatchVideoInsteadOfPlayingAudio(watch: Boolean)

    /**
     * Whether a radio queue should carry audio only, dropping the video entries YouTube mixes in.
     *
     * Scoped to radios on purpose — it is not a global "hide every video" switch, so a playlist or
     * an album the user picked themselves still plays exactly what it contains.
     */
    val radioAudioOnly: Flow<String>

    suspend fun setRadioAudioOnly(audioOnly: Boolean)

    val playerVolume: Flow<Float>

    suspend fun setPlayerVolume(volume: Float)

    val videoQuality: Flow<String>

    suspend fun setVideoQuality(quality: String)

    val spdc: Flow<String>

    suspend fun setSpdc(spdc: String)

    /**
     * Whether following an artist in the app also subscribes to their YouTube channel.
     *
     * Off by default and only settable while signed in: Follow has always been local-only, and
     * writing to someone's account is not something to start doing without being asked.
     */
    /**
     * Ten equalizer band gains in dB, comma separated, or empty for flat.
     *
     * Stored as text rather than ten keys because the bands are only ever read and written
     * together — a curve is one setting, not ten.
     */
    /**
     * Whether the equalizer is applied at all.
     *
     * Separate from the curve so switching it off keeps the shape the user built — turning it back
     * on returns to their settings rather than to flat.
     */
    val equalizerEnabled: Flow<String>

    suspend fun setEqualizerEnabled(enabled: Boolean)

    val equalizerBands: Flow<String>

    suspend fun setEqualizerBands(bandsDb: List<Float>)

    /** Equalizer preamp in dB. Usually negative: it is what stops a boosted curve from clipping. */
    val equalizerPreamp: Flow<Float>

    suspend fun setEqualizerPreamp(preampDb: Float)

    /**
     * The AutoEq profile last imported, as `"<label>\n<comma-separated gains>"`.
     *
     * Label and curve share one key on purpose. The label is only shown while the equalizer still
     * holds exactly those gains, so storing them apart would create two values that have to agree
     * — and a moment during the write where they do not.
     */
    val equalizerAutoEqProfile: Flow<String>

    suspend fun setEqualizerAutoEqProfile(
        label: String,
        bandsDb: List<Float>,
    )

    val syncFollowToYouTube: Flow<String>

    suspend fun setSyncFollowToYouTube(enabled: Boolean)

    val spotifyLyrics: Flow<String>

    suspend fun setSpotifyLyrics(spotifyLyrics: Boolean)

    val spotifyCanvas: Flow<String>

    suspend fun setSpotifyCanvas(spotifyCanvas: Boolean)

    val spotifyClientToken: Flow<String>

    suspend fun setSpotifyClientToken(token: String)

    val spotifyClientTokenExpires: Flow<Long>

    suspend fun setSpotifyClientTokenExpires(expires: Long)

    val tidalClientId: Flow<String>

    suspend fun setTidalClientId(value: String)

    val tidalClientSecret: Flow<String>

    suspend fun setTidalClientSecret(value: String)

    val spotifyPersonalToken: Flow<String>

    suspend fun setSpotifyPersonalToken(token: String)

    val spotifyPersonalTokenExpires: Flow<Long>

    suspend fun setSpotifyPersonalTokenExpires(expires: Long)

    val homeLimit: Flow<Int>

    suspend fun setHomeLimit(limit: Int)

    val chartKey: Flow<String>

    suspend fun setChartKey(key: String)

    val translucentBottomBar: Flow<String>

    suspend fun setTranslucentBottomBar(translucent: Boolean)

    val usingProxy: Flow<String>

    suspend fun setUsingProxy(usingProxy: Boolean)

    val proxyType: Flow<ProxyType>

    suspend fun setProxyType(proxyType: ProxyType)

    val proxyHost: Flow<String>

    suspend fun setProxyHost(proxyHost: String)

    val proxyPort: Flow<Int>

    suspend fun setProxyPort(proxyPort: Int)

    val proxyUsername: Flow<String>

    suspend fun setProxyUsername(proxyUsername: String)

    val proxyPassword: Flow<String>

    suspend fun setProxyPassword(proxyPassword: String)

    fun getJVMProxy(): ProxyConfiguration?

    val endlessQueue: Flow<String>

    suspend fun setEndlessQueue(endlessQueue: Boolean)

    val keepYouTubePlaylistOffline: Flow<String>

    suspend fun setKeepYouTubePlaylistOffline(keep: Boolean)

    val combineLocalAndYouTubeLiked: Flow<String>

    suspend fun setCombineLocalAndYouTubeLiked(combine: Boolean)

    val shouldShowLogInRequiredAlert: Flow<String>

    suspend fun setShouldShowLogInRequiredAlert(shouldShow: Boolean)

    val autoCheckForUpdates: Flow<String>

    suspend fun setAutoCheckForUpdates(autoCheck: Boolean)

    val updateChannel: Flow<String>

    suspend fun setUpdateChannel(channel: String)

    val playbackSpeed: Flow<Float>

    fun setPlaybackSpeed(speed: Float)

    val pitch: Flow<Int>

    fun setPitch(pitch: Int)

    val dataSyncId: Flow<String>

    suspend fun setDataSyncId(dataSyncId: String)

    val visitorData: Flow<String>

    suspend fun setVisitorData(visitorData: String)

    suspend fun setAIProvider(provider: String)

    val aiProvider: Flow<String>

    suspend fun setAIApiKey(apiKey: String)

    val aiApiKey: Flow<String>

    val useAITranslation: Flow<String>

    suspend fun setUseAITranslation(use: Boolean)

    val customModelId: Flow<String>

    suspend fun setCustomModelId(modelId: String)

    val customOpenAIBaseUrl: Flow<String>

    suspend fun setCustomOpenAIBaseUrl(baseUrl: String)

    val customOpenAIHeaders: Flow<String>

    suspend fun setCustomOpenAIHeaders(headers: String)

    val localPlaylistFilter: Flow<String>

    suspend fun setLocalPlaylistFilter(filter: String)

    val killServiceOnExit: Flow<String>

    suspend fun setKillServiceOnExit(kill: Boolean)

    val keepServiceAlive: Flow<String>

    suspend fun setKeepServiceAlive(keep: Boolean)

    val crossfadeEnabled: Flow<String>

    suspend fun setCrossfadeEnabled(enabled: Boolean)

    val crossfadeDuration: Flow<Int>

    suspend fun setCrossfadeDuration(duration: Int)

    val crossfadeDjMode: Flow<String>

    suspend fun setCrossfadeDjMode(enabled: Boolean)

    /**
     * When on, transitions *between tracks of the same album* skip the crossfade, so an album that
     * was sequenced to run continuously keeps doing so. Edges still crossfade: the last album track
     * into whatever follows it fades normally. Off by default — it changes how crossfade behaves
     * for anyone already using it.
     */
    val crossfadeSkipAlbum: Flow<String>

    suspend fun setCrossfadeSkipAlbum(enabled: Boolean)

    /**
     * When on, liking a song also queues it for offline download, at the existing download quality.
     *
     * Off by default — it spends storage and data without the user asking each time. Only applies
     * from the moment it is switched on: songs liked earlier are left alone, and unliking never
     * removes a download that already exists.
     */
    val autoDownloadLikedSongs: Flow<String>

    suspend fun setAutoDownloadLikedSongs(enabled: Boolean)

    val youtubeSubtitleLanguage: Flow<String>

    suspend fun setYoutubeSubtitleLanguage(language: String)

    val helpBuildLyricsDatabase: Flow<String>

    suspend fun setHelpBuildLyricsDatabase(help: Boolean)

    val contributorName: Flow<String>
    val contributorEmail: Flow<String>

    suspend fun setContributorLyricsDatabase(contributor: Pair<String, String>?)

    val backupDownloaded: Flow<String>

    suspend fun setBackupDownloaded(backupDownloaded: Boolean)

    val enableLiquidGlass: Flow<String>

    suspend fun setEnableLiquidGlass(enable: Boolean)

    /** One of [THEME_MODE_SYSTEM], [THEME_MODE_DARK], [THEME_MODE_LIGHT]. */
    val themeMode: Flow<String>

    suspend fun setThemeMode(mode: String)

    /** One of [THEME_COLOR_DEFAULT], [THEME_COLOR_WALLPAPER], [THEME_COLOR_CUSTOM]. */
    val themeColorSource: Flow<String>

    suspend fun setThemeColorSource(source: String)

    /** Seed color for the custom theme as an 8-digit ARGB hex string (e.g. "FF8ECAE6"). */
    val customThemeColor: Flow<String>

    suspend fun setCustomThemeColor(argbHex: String)

    /** One of [NOW_PLAYING_STYLE_SPOTIFY], [NOW_PLAYING_STYLE_M3_EXPRESSIVE], [NOW_PLAYING_STYLE_APPLE_MUSIC]. */
    val nowPlayingStyle: Flow<String>

    suspend fun setNowPlayingStyle(style: String)

    /**
     * One of [LYRICS_STYLE_CLASSIC], [LYRICS_STYLE_APPLE_MUSIC]. Deliberately independent of
     * [nowPlayingStyle]: it governs how a lyric line is drawn, everywhere lyrics are drawn —
     * the fullscreen sheet included, whichever player style is in use.
     */
    val lyricsStyle: Flow<String>

    suspend fun setLyricsStyle(style: String)

    /**
     * Which languages get a Latin-script reading shown for their lyrics, as a comma-separated list
     * of [org.simpmusic.lyrics.romanization.RomanizationLanguage] NAMES — empty string means the
     * feature is off, which is the default.
     *
     * Stored as names rather than ordinals because an enum's ordinal is a position, not an
     * identity: inserting a language into the middle of that enum would silently repoint every
     * saved preference. Stored as ONE key rather than twelve booleans so reading it is a single
     * flow rather than a combine of twelve.
     */
    val romanizationLanguages: Flow<String>

    suspend fun setRomanizationLanguages(languages: String)

    val explicitContentEnabled: Flow<String>

    suspend fun setExplicitContentEnabled(enabled: Boolean)

    val discordToken: Flow<String>

    suspend fun setDiscordToken(token: String)

    val richPresenceEnabled: Flow<String>

    suspend fun setRichPresenceEnabled(enabled: Boolean)

    /** Last.fm session key. Has no expiry — it stays valid until the user revokes it on last.fm. */
    val lastfmSessionKey: Flow<String>

    /** The logged-in Last.fm username, kept only so settings can show whose account is connected. */
    val lastfmUsername: Flow<String>

    suspend fun setLastfmSession(
        sessionKey: String,
        username: String,
    )

    val lastfmScrobbleEnabled: Flow<String>

    suspend fun setLastfmScrobbleEnabled(enabled: Boolean)

    val localTrackingEnabled: Flow<String>

    suspend fun setLocalTrackingEnabled(enabled: Boolean)

    val blogNotificationEnabled: Flow<String>

    suspend fun setBlogNotificationEnabled(enabled: Boolean)

    // Auto Backup
    val autoBackupEnabled: Flow<String>

    suspend fun setAutoBackupEnabled(enabled: Boolean)

    val autoBackupFrequency: Flow<String>

    suspend fun setAutoBackupFrequency(frequency: String)

    val autoBackupMaxFiles: Flow<Int>

    suspend fun setAutoBackupMaxFiles(max: Int)

    val autoBackupLastTime: Flow<Long>

    suspend fun setAutoBackupLastTime(time: Long)

    enum class ProxyType {
        PROXY_TYPE_HTTP,
        PROXY_TYPE_SOCKS,
    }

    companion object Values {
        const val SIMPMUSIC = "simpmusic"
        const val YOUTUBE = "youtube"
        const val LRCLIB = "lrclib"
        const val BETTER_LYRICS = "better_lyrics"

        const val FDROID = "fdroid"
        const val GITHUB_FOSS_NIGHTLY = "github_foss_nightly"
        const val GITHUB = "github_release"

        const val REPEAT_MODE_OFF = "REPEAT_MODE_OFF"
        const val REPEAT_ONE = "REPEAT_ONE"
        const val REPEAT_ALL = "REPEAT_ALL"

        const val TRUE = "TRUE"
        const val FALSE = "FALSE"

        const val THEME_MODE_SYSTEM = "SYSTEM"
        const val THEME_MODE_DARK = "DARK"
        const val THEME_MODE_LIGHT = "LIGHT"

        const val THEME_COLOR_DEFAULT = "DEFAULT"
        const val THEME_COLOR_WALLPAPER = "WALLPAPER"
        const val THEME_COLOR_CUSTOM = "CUSTOM"

        const val DEFAULT_THEME_COLOR_HEX = "FF8ECAE6"

        const val NOW_PLAYING_STYLE_SPOTIFY = "SPOTIFY"
        const val NOW_PLAYING_STYLE_M3_EXPRESSIVE = "M3_EXPRESSIVE"
        const val NOW_PLAYING_STYLE_APPLE_MUSIC = "APPLE_MUSIC"

        const val LYRICS_STYLE_CLASSIC = "CLASSIC"
        const val LYRICS_STYLE_APPLE_MUSIC = "APPLE_MUSIC"

        const val CROSSFADE_DURATION_AUTO = 0

        const val PROXY_TYPE_HTTP = "http"
        const val PROXY_TYPE_SOCKS = "socks"

        // AI
        const val AI_PROVIDER_GEMINI = "gemini"
        const val AI_PROVIDER_OPENAI = "openai"
        const val AI_PROVIDER_CUSTOM_OPENAI = "custom_openai"

        const val LOCAL_PLAYLIST_FILTER_OLDER_FIRST = "older_first"
        const val LOCAL_PLAYLIST_FILTER_NEWER_FIRST = "newer_first"
        const val LOCAL_PLAYLIST_FILTER_TITLE = "title"
        const val LOCAL_PLAYLIST_FILTER_CUSTOM_ORDER = "custom_order"

        // Auto Backup Frequency
        const val AUTO_BACKUP_FREQUENCY_DAILY = "daily"
        const val AUTO_BACKUP_FREQUENCY_WEEKLY = "weekly"
        const val AUTO_BACKUP_FREQUENCY_MONTHLY = "monthly"
    }
}