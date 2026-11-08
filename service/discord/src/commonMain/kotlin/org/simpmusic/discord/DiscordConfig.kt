package org.simpmusic.discord

import kotlin.concurrent.Volatile

@Volatile
private var applicationId: String = ""

/**
 * Sets the Discord application this build logs in with. Called once at startup, before Koin, with
 * the id BuildKonfig carries — which only a Full build has. A blank id means this build has no
 * Discord application, and Rich Presence keeps going through kizzy.
 */
fun configDiscord(clientId: String) {
    applicationId = clientId.trim()
}

fun isDiscordOAuthAvailable(): Boolean = applicationId.isNotEmpty()

internal fun discordClientId(): String = applicationId
