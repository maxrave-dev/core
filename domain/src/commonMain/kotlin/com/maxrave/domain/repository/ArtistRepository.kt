package com.maxrave.domain.repository

import com.maxrave.domain.data.entities.ArtistEntity
import com.maxrave.domain.data.model.browse.artist.ArtistBrowse
import com.maxrave.domain.utils.Resource
import kotlinx.coroutines.flow.Flow
import kotlinx.datetime.LocalDateTime

interface ArtistRepository {
    fun getAllArtists(limit: Int): Flow<List<ArtistEntity>>

    fun getArtistById(id: String): Flow<ArtistEntity?>

    suspend fun insertArtist(artistEntity: ArtistEntity)

    suspend fun updateArtistImage(
        channelId: String,
        thumbnail: String,
    )

    suspend fun updateArtistNameLogo(
        channelId: String,
        nameLogoUrl: String?,
        nameLogoColor: String?,
    )

    /**
     * Records the follow locally, and mirrors it onto the YouTube account when that is enabled.
     *
     * Returns null when mirroring is off or was not attempted, true when the account was updated,
     * false when the call failed — the caller decides whether that is worth telling the user
     * about. The local flag is written either way: Follow must not depend on the network.
     */
    suspend fun updateFollowedStatus(
        channelId: String,
        followedStatus: Int,
    ): Boolean?

    /**
     * Subscribes to every artist already followed locally.
     *
     * Turning the setting on is a statement about the whole library, not about the next artist
     * tapped — without this, artists followed before the switch stay invisible to the account.
     * Emits the number that succeeded and the number attempted.
     */
    fun syncFollowedArtistsToYouTube(): Flow<Pair<Int, Int>>

    fun getFollowedArtists(): Flow<List<ArtistEntity>>

    suspend fun updateArtistInLibrary(
        inLibrary: LocalDateTime,
        channelId: String,
    )

    fun getArtistData(channelId: String): Flow<Resource<ArtistBrowse>>
}