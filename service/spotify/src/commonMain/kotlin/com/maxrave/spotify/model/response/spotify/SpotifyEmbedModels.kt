package com.maxrave.spotify.model.response.spotify

import kotlinx.serialization.Serializable

@Serializable
data class SpotifyEmbedNextData(
    val props: SpotifyEmbedProps? = null,
)

@Serializable
data class SpotifyEmbedProps(
    val pageProps: SpotifyEmbedPageProps? = null,
)

@Serializable
data class SpotifyEmbedPageProps(
    val state: SpotifyEmbedState? = null,
)

@Serializable
data class SpotifyEmbedState(
    val data: SpotifyEmbedStateData? = null,
)

@Serializable
data class SpotifyEmbedStateData(
    val entity: SpotifyEmbedEntity? = null,
)

@Serializable
data class SpotifyEmbedEntity(
    val id: String = "",
    val name: String = "",
    val title: String? = null,
    val type: String = "playlist",
    val subtitle: String? = null,
    val trackList: List<SpotifyEmbedTrackItem> = emptyList(),
    val visualIdentity: SpotifyEmbedVisualIdentity? = null,
)

@Serializable
data class SpotifyEmbedVisualIdentity(
    val image: List<SpotifyEmbedImage> = emptyList(),
)

@Serializable
data class SpotifyEmbedImage(
    val url: String = "",
    val maxHeight: Int? = null,
    val maxWidth: Int? = null,
)

@Serializable
data class SpotifyEmbedTrackItem(
    val uri: String? = null,
    val uid: String? = null,
    val title: String? = null,
    val subtitle: String? = null,
    val duration: Long? = null,
    val isPlayable: Boolean? = null,
    val entityType: String? = null,
)
