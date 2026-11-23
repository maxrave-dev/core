package com.maxrave.kotlinytmusicscraper.models

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private fun feedback(vararg params: Pair<String, String>) =
    ResponseContext.ServiceTrackingParam(
        service = "GFEEDBACK",
        params = params.map { ResponseContext.ServiceTrackingParam.Param(key = it.first, value = it.second) },
    )

class ResponseContextLoginStateTest {
    @Test
    fun explicitZeroMeansLoggedOut() {
        assertTrue(ResponseContext(serviceTrackingParams = listOf(feedback("logged_in" to "0"))).isLoggedOut())
    }

    @Test
    fun loggedInOneIsNotLoggedOut() {
        assertFalse(ResponseContext(serviceTrackingParams = listOf(feedback("logged_in" to "1"))).isLoggedOut())
    }

    @Test
    fun missingParamIsNotEvidenceOfLogout() {
        assertFalse(ResponseContext(serviceTrackingParams = listOf(feedback("visitor_data" to "abc"))).isLoggedOut())
        assertFalse(ResponseContext(serviceTrackingParams = null).isLoggedOut())
    }

    @Test
    fun onlyTheFeedbackServiceIsRead() {
        val other =
            ResponseContext.ServiceTrackingParam(
                service = "OTHER",
                params = listOf(ResponseContext.ServiceTrackingParam.Param(key = "logged_in", value = "0")),
            )
        assertFalse(ResponseContext(serviceTrackingParams = listOf(other)).isLoggedOut())
    }

    @Test
    fun findsTheParamAmongSiblings() {
        assertTrue(
            ResponseContext(
                serviceTrackingParams = listOf(feedback("visitor_data" to "abc", "logged_in" to "0")),
            ).isLoggedOut(),
        )
    }
}
