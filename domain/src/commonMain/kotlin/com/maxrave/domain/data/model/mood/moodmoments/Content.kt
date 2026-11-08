package com.maxrave.domain.data.model.mood.moodmoments

import com.maxrave.domain.data.model.searchResult.songs.Thumbnail
import com.maxrave.domain.data.type.HomeContentType

data class Content(
    val playlistBrowseId: String,
    val subtitle: String,
    val thumbnails: List<Thumbnail>?,
    val title: String,
    /**
     * Set only for entries of the "Songs" shelf, which YouTube sends as
     * `musicResponsiveListItemRenderer` instead of the `musicTwoRowItemRenderer` used by every
     * other shelf. Null means this is a playlist and [playlistBrowseId] is the one to open.
     */
    val videoId: String? = null,
) : HomeContentType
