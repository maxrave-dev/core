package com.maxrave.domain.data.model.home

import com.maxrave.domain.data.model.searchResult.songs.Thumbnail

data class HomeItem(
    val contents: List<Content?>,
    val title: String,
    val subtitle: String? = null,
    val thumbnail: List<Thumbnail>? = null,
    val channelId: String? = null,
    val moreEndpoint: MoreEndpoint? = null,
) {
    /**
     * Where the section's "More" button leads on YouTube Music — a browse page, not a play action.
     * Null when the section ships no such button, which is common: the button is per section.
     *
     * [pageType] is YouTube's own statement of what the target is (`MUSIC_PAGE_TYPE_*`), so routing
     * follows it instead of guessing from the id. `FEmusic_*` pages carry none: they are generic
     * shelf pages.
     */
    data class MoreEndpoint(
        val browseId: String,
        val params: String? = null,
        val pageType: String? = null,
    ) {
        companion object {
            const val PAGE_TYPE_ALBUM = "MUSIC_PAGE_TYPE_ALBUM"
            const val PAGE_TYPE_AUDIOBOOK = "MUSIC_PAGE_TYPE_AUDIOBOOK"
            const val PAGE_TYPE_PLAYLIST = "MUSIC_PAGE_TYPE_PLAYLIST"
            const val PAGE_TYPE_ARTIST = "MUSIC_PAGE_TYPE_ARTIST"
            const val PAGE_TYPE_USER_CHANNEL = "MUSIC_PAGE_TYPE_USER_CHANNEL"
            const val PAGE_TYPE_PODCAST = "MUSIC_PAGE_TYPE_PODCAST_SHOW_DETAIL_PAGE"
        }
    }
}