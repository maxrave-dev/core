package com.maxrave.domain.data.model.promo

import kotlinx.serialization.Serializable

/**
 * A launch banner from the remote config's `promos` block. Every field has a default, so an entry
 * missing one still decodes and is then skipped by the app; the app decodes entry by entry, so one
 * written with a wrong type drops alone instead of taking the whole list down.
 */
@Serializable
data class Promo(
    /** Names the banner, so the app can tell which ones it has shown. Required. */
    val id: String? = null,
    /** Language code to portrait banner URL. "en" stands in for any language without its own. */
    val image: Map<String, String> = emptyMap(),
    /** A simpmusic:// deep link or an https:// page. */
    val link: String? = null,
    /** Lowest app version that understands [link], e.g. "2.3.0". */
    val minVersion: String? = null,
    /** Conditions the app must meet. One a build does not know hides the banner from that build. */
    val requires: List<String> = emptyList(),
) {
    companion object {
        /** The last fetched `promos` block, kept for the next launch. Dev builds keep their own. */
        fun cacheKey(isDevBuild: Boolean) = if (isDevBuild) "remote_promos_dev" else "remote_promos"

        /** The banners this app version has shown: each banner once per version. */
        fun shownKey(isDevBuild: Boolean) = if (isDevBuild) "promo_shown_dev" else "promo_shown"
    }
}
