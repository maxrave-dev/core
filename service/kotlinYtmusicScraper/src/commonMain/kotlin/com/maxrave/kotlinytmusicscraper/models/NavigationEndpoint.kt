package com.maxrave.kotlinytmusicscraper.models

import kotlinx.serialization.Serializable

@Serializable
data class NavigationEndpoint(
    val watchEndpoint: WatchEndpoint? = null,
    val watchPlaylistEndpoint: WatchEndpoint? = null,
    val browseEndpoint: BrowseEndpoint? = null,
    val searchEndpoint: SearchEndpoint? = null,
    val urlEndpoint: UrlEndpoint? = null,
    val queueAddEndpoint: QueueAddEndpoint? = null,
    val shareEntityEndpoint: ShareEntityEndpoint? = null,
    val playlistEditEndpoint: PlaylistEditEndpoint? = null,
    val executeEntityCommand: ExecuteEntityCommand? = null,
) {
    /** A key into the response's `frameworkUpdates`, where the real command sits (Liked Music's Shuffle). */
    @Serializable
    data class ExecuteEntityCommand(
        val commandEntityKey: String? = null,
    )

    val endpoint: Endpoint?
        get() =
            watchEndpoint
                ?: watchPlaylistEndpoint
                ?: browseEndpoint
                ?: searchEndpoint
                ?: queueAddEndpoint
                ?: shareEntityEndpoint
}