package com.maxrave.kotlinytmusicscraper.models.body

import com.maxrave.kotlinytmusicscraper.models.Context
import kotlinx.serialization.Serializable

/**
 * Body for `subscription/subscribe` and `subscription/unsubscribe`.
 *
 * [channelIds] is a list because the endpoint accepts several channels at once, even though a
 * Follow only ever concerns one.
 *
 * [params] is optional and left null: the token carried by a channel page's subscribe button is
 * not required for the call to succeed, so the channel id alone is enough to act on.
 */
@Serializable
data class SubscribeBody(
    val context: Context,
    val channelIds: List<String>,
    val params: String? = null,
)
