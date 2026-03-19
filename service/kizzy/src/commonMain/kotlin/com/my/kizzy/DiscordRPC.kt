package com.my.kizzy

import com.maxrave.domain.data.entities.SongEntity
import com.my.kizzy.rpc.KizzyRPC
import com.my.kizzy.rpc.RpcImage
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

class DiscordRPC(
    token: String
) : KizzyRPC(token) {
    @OptIn(ExperimentalTime::class)
    suspend fun updateSong(song: SongEntity) = runCatching {
        setActivity(
            name = APP_NAME,
            details = song.title,
            state = song.artistName?.joinToString(", "),
            largeImage = song.thumbnails?.let { RpcImage.ExternalImage(it) },
            smallImage = RpcImage.ExternalImage(APP_ICON),
            largeText = song.albumName,
            smallText = song.artistName?.firstOrNull(),
            buttons = listOf(
                "Listen on SimpMusic" to "https://simpmusic.org/app/watch?v=${song.videoId}",
                "Visit SimpMusic" to "https://github.com/maxrave-dev/SimpMusic"
            ),
            type = Type.LISTENING,
            since = Clock.System.now().toEpochMilliseconds(),
            applicationId = APPLICATION_ID
        )
    }

    companion object {
        private const val APPLICATION_ID = "1271273225120125040"
        private const val APP_NAME: String = "SimpMusic"
        private const val APP_ICON: String =
            "https://fra.cloud.appwrite.io/v1/storage/buckets/683f1f620010ba0fa5b1/files/69007bc8001a28a7cea8/view?project=67ec0369002bd8a96885"
    }
}