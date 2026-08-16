package com.maxrave.domain.data.model.home

import com.maxrave.domain.data.model.searchResult.songs.Album
import com.maxrave.domain.data.model.searchResult.songs.Artist
import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.domain.data.type.HomeContentType

data class Content(
    val album: Album?,
    val artists: List<Artist>?,
    val description: String?,
    val isExplicit: Boolean?,
    val playlistId: String?,
    val browseId: String?,
    val thumbnails: List<Thumbnail>,
    val title: String,
    val videoId: String?,
    val views: String?,
    val durationSeconds: Int? = null,
    val radio: String? = null,
    /**
     * YouTube's `MUSIC_VIDEO_TYPE_*` for the track behind this card, or null when the shelf item
     * is not a track (artist, playlist, album) or carried no music config.
     */
    val videoType: String? = null,
) : HomeContentType