package com.maxrave.common

/** Supplied by the application entry point so build variants retain their real identity. */
data class AppIdentity(
    val applicationId: String,
    val versionName: String,
    val platform: String,
    /** Debug builds and Gradle runs. They read the test remote config and keep their cached copy of it apart. */
    val isDevBuild: Boolean = false,
) {
    val userAgent: String
        get() = "SimpMusic/$versionName ($applicationId; $platform)"
}
