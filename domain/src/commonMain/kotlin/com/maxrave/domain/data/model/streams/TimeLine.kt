package com.maxrave.domain.data.model.streams

data class TimeLine(
    val current: Long,
    val total: Long,
    val bufferedPercent: Int,
    val loading: Boolean = true,
    val isCrossfading: Boolean = false,
    // A live stream has no length: `total` means nothing, and seek bars show LIVE instead.
    val isLive: Boolean = false,
)