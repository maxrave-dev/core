package com.marki19.domain.jam

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

interface JamRepository {
    val sessionState: StateFlow<JamSessionState?>
    val chatMessages: StateFlow<List<JamCommand.ChatMessage>>
    val incomingCommands: SharedFlow<JamCommand>

    suspend fun createSession(userId: String, name: String, imageUrl: String): Result<String>
    suspend fun joinSession(roomId: String, userId: String, name: String, imageUrl: String): Result<Unit>
    suspend fun leaveSession()

    suspend fun sendCommand(command: JamCommand)
    suspend fun updatePermissions(permissions: JamPermissions)
    suspend fun syncState(state: JamPlaybackState)
}
