package com.maxrave.domain.repository

import com.maxrave.domain.data.entities.LyricsEntity
import com.maxrave.domain.data.entities.TranslatedLyricsEntity
import com.maxrave.domain.data.model.browse.album.Track
import com.maxrave.domain.data.model.browse.artist.ArtistEditorial
import com.maxrave.domain.data.model.canvas.CanvasResult
import com.maxrave.domain.data.model.metadata.Lyrics
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.flow.Flow

interface LyricsCanvasRepository {
    fun getSavedLyrics(videoId: String): Flow<LyricsEntity?>

    suspend fun insertLyrics(lyricsEntity: LyricsEntity)

    suspend fun insertTranslatedLyrics(translatedLyrics: TranslatedLyricsEntity)

    fun getSavedTranslatedLyrics(
        videoId: String,
        language: String,
    ): Flow<TranslatedLyricsEntity?>

    suspend fun removeTranslatedLyrics(
        videoId: String,
        language: String,
    )

    fun getYouTubeCaption(
        preferLang: String,
        videoId: String,
    ): Flow<Resource<Pair<Lyrics, Lyrics?>>>

    fun getCanvas(
        dataStoreManager: DataStoreManager,
        videoId: String,
        duration: Int,
    ): Flow<Resource<CanvasResult>>

    /**
     * Animated album artwork from the hidden AM catalog, returned as the same [CanvasResult] a
     * Spotify canvas produces and stored in the same columns. Needs no login.
     */
    fun getAMAnimatedArtwork(videoId: String): Flow<Resource<CanvasResult>>

    suspend fun updateCanvasUrl(
        videoId: String,
        canvasUrl: String,
    )

    suspend fun updateCanvasThumbUrl(
        videoId: String,
        canvasThumbUrl: String,
    )

    fun getSpotifyLyrics(
        dataStoreManager: DataStoreManager,
        query: String,
        duration: Int?,
    ): Flow<Resource<Lyrics>>

    fun getLrclibLyricsData(
        sartist: String,
        strack: String,
        duration: Int?,
    ): Flow<Resource<Lyrics>>

    fun getBetterLyrics(
        artist: String,
        track: String,
        duration: Int?,
    ): Flow<Resource<Lyrics>>

    /**
     * Fetch everything the hidden catalog holds for an artist: the name-logo image with its
     * dominant color, and the animated artist artwork.
     *
     * One call, because both ride the same response — splitting them would double a pair of
     * requests against an API that is not ours. Either half may be null on a success.
     */
    fun getArtistEditorial(artistName: String): Flow<Resource<ArtistEditorial>>

    fun getAITranslationLyrics(
        lyrics: Lyrics,
        targetLanguage: String,
    ): Flow<Resource<Lyrics>>

    fun getSimpMusicLyrics(videoId: String): Flow<Resource<Lyrics>>

    fun getSimpMusicTranslatedLyrics(
        videoId: String,
        language: String,
    ): Flow<Resource<Lyrics>>

    fun voteSimpMusicLyrics(
        lyricsId: String,
        upvote: Boolean,
    ): Flow<Resource<String>>

    fun voteSimpMusicTranslatedLyrics(
        translatedLyricsId: String,
        upvote: Boolean,
    ): Flow<Resource<String>>

    fun insertSimpMusicLyrics(
        dataStoreManager: DataStoreManager,
        track: Track,
        duration: Int,
        lyrics: Lyrics,
    ): Flow<Resource<String>>

    fun insertSimpMusicTranslatedLyrics(
        dataStoreManager: DataStoreManager,
        track: Track,
        translatedLyrics: Lyrics,
        language: String,
    ): Flow<Resource<String>>
}