package com.maxrave.domain.data.model.library

import com.maxrave.domain.data.model.searchResult.playlists.PlaylistsResult

/**
 * What the Library's "Your YouTube Playlists" tab draws, and where the list came from.
 *
 * [Source.SAVED_ON_DEVICE] means the tiles were served from the local cache — either because
 * YouTube answered with a logged-out session or because the request failed. [sessionExpired] is set
 * only for the first case, where YouTube itself reported `logged_in=0`, so a transient network
 * failure is never presented as the account having been signed out.
 */
data class YouTubeLibraryPlaylists(
    val playlists: List<PlaylistsResult>,
    val source: Source,
    val sessionExpired: Boolean,
) {
    enum class Source {
        ONLINE,
        SAVED_ON_DEVICE,
    }
}
