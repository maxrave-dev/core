package com.maxrave.kotlinytmusicscraper.models

import kotlinx.serialization.Serializable

@Serializable
data class ResponseContext(
    val visitorData: String? = null,
    val serviceTrackingParams: List<ServiceTrackingParam>?,
    val mainAppWebResponseContext: MainAppWebResponseContext? = null,
    val webResponseContextExtensionData: WebResponseContextExtensionData? = null,
) {
    @Serializable
    data class WebResponseContextExtensionData(
        val ytConfigData: YtConfigData? = null,
    ) {
        @Serializable
        data class YtConfigData(
            val visitorData: String? = null,
        )
    }
    @Serializable
    data class MainAppWebResponseContext(
        val datasyncId: String? = null,
    )
    @Serializable
    data class ServiceTrackingParam(
        val params: List<Param>,
        val service: String,
    ) {
        @Serializable
        data class Param(
            val key: String,
            val value: String,
        )
    }
}

/**
 * Whether YouTube served this response to an anonymous visitor.
 *
 * `GFEEDBACK` carries `logged_in=0` in that case — the visible symptom is a login prompt instead
 * of the requested shelf. An absent param proves nothing, so only an explicit `0` counts.
 */
fun ResponseContext.isLoggedOut(): Boolean =
    serviceTrackingParams
        ?.firstOrNull { it.service == "GFEEDBACK" }
        ?.params
        ?.firstOrNull { it.key == "logged_in" }
        ?.value == "0"
