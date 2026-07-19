package com.marki19.data.repository.jam

import com.marki19.domain.jam.JamCommand
import com.marki19.domain.jam.JamParticipant
import com.marki19.domain.jam.JamPermissions
import com.marki19.domain.jam.JamPlaybackState
import com.marki19.domain.jam.JamQueueItem
import com.marki19.domain.jam.JamRepeatMode
import com.marki19.domain.jam.JamRepository
import com.marki19.domain.jam.JamSessionState
import com.marki19.jamsync.JamMessage
import com.marki19.jamsync.JamSyncClient
import kotlin.time.ExperimentalTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import io.ktor.util.date.getTimeMillis

@OptIn(ExperimentalTime::class)
class JamRepositoryImpl(
    private val jamClient: JamSyncClient
) : JamRepository {

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val _sessionState = MutableStateFlow<JamSessionState?>(null)
    override val sessionState: StateFlow<JamSessionState?> = _sessionState.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<JamCommand.ChatMessage>>(emptyList())
    override val chatMessages: StateFlow<List<JamCommand.ChatMessage>> = _chatMessages.asStateFlow()

    private val _incomingCommands = MutableSharedFlow<JamCommand>(extraBufferCapacity = 32)
    override val incomingCommands: SharedFlow<JamCommand> = _incomingCommands.asSharedFlow()

    private var localUserId: String = ""
    private var connectionJob: Job? = null

    // ── Helper: safe json extraction ─────────────────────────────────────────

    private fun JsonObject?.string(key: String) =
        this?.get(key)?.jsonPrimitive?.content

    private fun JsonObject?.long(key: String) =
        this?.get(key)?.jsonPrimitive?.longOrNull

    private fun JsonObject?.int(key: String) =
        this?.get(key)?.jsonPrimitive?.intOrNull

    private fun JsonObject?.bool(key: String) =
        this?.get(key)?.jsonPrimitive?.booleanOrNull

    // ── Parse queue item from server JSON ────────────────────────────────────

    private fun parseQueueItem(obj: kotlinx.serialization.json.JsonElement): JamQueueItem? {
        val j = obj.jsonObject
        val queueId = j.string("queueId") ?: return null
        val videoId = j.string("videoId") ?: return null
        val voterIdsArr = j["voterIds"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content }?.toSet() ?: emptySet()
        return JamQueueItem(
            queueId = queueId,
            videoId = videoId,
            title = j.string("title") ?: "",
            artist = j.string("artist") ?: "",
            thumbnailUrl = j.string("thumbnailUrl"),
            durationMs = j.long("durationMs") ?: 0L,
            addedBy = j.string("addedBy") ?: "",
            addedTimestamp = j.long("addedTimestamp") ?: 0L,
            voteCount = j.int("voteCount") ?: 0,
            voterIds = voterIdsArr,
            orderWeight = j["orderWeight"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
            isRecommendation = j.bool("isRecommendation") ?: false,
        )
    }

    private fun parseQueue(payload: JsonObject?, key: String): List<JamQueueItem> {
        return payload?.get(key)?.jsonArray?.mapNotNull { parseQueueItem(it) } ?: emptyList()
    }

    // ── Map server 'state' object → JamPlaybackState ─────────────────────────

    private fun parsePlaybackState(stateObj: JsonObject?): JamPlaybackState {
        if (stateObj == null) return JamPlaybackState()
        val repeatRaw = stateObj.string("repeatMode") ?: "OFF"
        val repeat = try { JamRepeatMode.valueOf(repeatRaw) } catch (_: Exception) { JamRepeatMode.OFF }
        return JamPlaybackState(
            currentSongId = stateObj.string("currentSongId"),
            isPlaying = stateObj.bool("isPlaying") ?: false,
            playbackPositionMs = stateObj.long("playbackPositionMs") ?: 0L,
            shuffle = stateObj.bool("shuffle") ?: false,
            repeatMode = repeat,
            serverTimestampMs = stateObj.long("serverTimestampMs") ?: 0L,
        )
    }

    // ── Map server 'permissions' object → JamPermissions ─────────────────────

    private fun parsePermissions(p: JsonObject?): JamPermissions {
        if (p == null) return JamPermissions()
        return JamPermissions(
            allowAddSongs = p.bool("allowAddSongs") ?: true,
            allowRemoveSongs = p.bool("allowRemoveSongs") ?: true,
            allowReorder = p.bool("allowReorder") ?: false,
            allowPause = p.bool("allowPause") ?: true,
            allowSkip = p.bool("allowSkip") ?: true,
            allowSeek = p.bool("allowSeek") ?: false,
            allowVoting = p.bool("allowVoting") ?: true,
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    init {
        scope.launch {
            jamClient.messages.collect { message ->
                handleServerMessage(message)
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private suspend fun handleServerMessage(message: JamMessage) {
        when (message.type) {

            "SESSION_CREATED" -> {
                _sessionState.value = JamSessionState(
                    roomId = message.roomId ?: "",
                    isHost = true,
                    hostId = localUserId,
                    permissions = JamPermissions(),
                    playbackState = JamPlaybackState(),
                )
            }

            "SESSION_JOINED" -> {
                val payload = message.payload
                val permObj = payload?.get("permissions")?.jsonObject
                val stateObj = payload?.get("state")?.jsonObject
                val queueArr = payload?.get("queue")?.jsonArray?.mapNotNull { parseQueueItem(it) } ?: emptyList()
                val hostId = payload?.string("hostId") ?: ""
                val recsEnabled = payload?.bool("recommendationsEnabled") ?: true

                val participantsArr = payload?.get("participants")?.jsonArray?.mapNotNull {
                    val obj = it.jsonObject
                    JamParticipant(
                        userId = obj.string("userId") ?: "",
                        name = obj.string("name") ?: "Guest",
                        imageUrl = obj.string("imageUrl") ?: "",
                        online = obj.bool("online") ?: true
                    )
                } ?: emptyList()

                _sessionState.value = JamSessionState(
                    roomId = message.roomId ?: "",
                    isHost = false,
                    hostId = hostId,
                    participants = participantsArr,
                    permissions = parsePermissions(permObj),
                    playbackState = parsePlaybackState(stateObj).copy(queue = queueArr),
                    recommendationsEnabled = recsEnabled,
                )
            }

            "PARTICIPANTS_UPDATED" -> {
                val participantsArr = message.payload?.get("participants")?.jsonArray?.mapNotNull {
                    val obj = it.jsonObject
                    JamParticipant(
                        userId = obj.string("userId") ?: "",
                        name = obj.string("name") ?: "Guest",
                        imageUrl = obj.string("imageUrl") ?: "",
                        online = obj.bool("online") ?: true
                    )
                } ?: return
                
                updateState { state ->
                    // Remove tastes of participants who have left entirely
                    val currentIds = participantsArr.map { it.userId }.toSet()
                    val newTastes = state.guestTastes.filterKeys { it in currentIds }
                    
                    state.copy(
                        participants = participantsArr,
                        guestTastes = newTastes
                    )
                }
            }


            "HOST_TRANSFERRED" -> {
                val newHostId = message.payload?.string("newHostId") ?: return
                val amINewHost = newHostId == localUserId
                updateState { state ->
                    state.copy(
                        hostId = newHostId,
                        isHost = amINewHost,
                        isSyncing = false,
                        newHostNotice = if (amINewHost) "You are now the host" else null,
                    )
                }
                if (amINewHost) {
                    // New host immediately syncs current state to guests
                    val currentPlayback = _sessionState.value?.playbackState ?: return
                    syncState(currentPlayback)
                }
            }

            "SESSION_ENDED" -> {
                _sessionState.value = null
                _chatMessages.value = emptyList()
            }

            "PERMISSIONS_UPDATED" -> {
                val permObj = message.payload?.get("permissions")?.jsonObject
                updateState { state ->
                    state.copy(permissions = parsePermissions(permObj))
                }
            }

            "STATE_SYNC" -> {
                val stateObj = message.payload?.get("state")?.jsonObject
                val parsed = parsePlaybackState(stateObj)
                updateState { state ->
                    state.copy(playbackState = parsed.copy(queue = state.playbackState.queue))
                }
            }

            "QUEUE_UPDATED" -> {
                val queueArr = message.payload?.get("queue")?.jsonArray?.mapNotNull { parseQueueItem(it) } ?: return
                updateState { state ->
                    state.copy(playbackState = state.playbackState.copy(queue = queueArr))
                }
                // Emit domain command so JamPlayerSynchronizer can react
                val reason = message.payload?.string("reason") ?: ""
                when (reason) {
                    "SONG_ADDED" -> {
                        val videoId = message.payload?.string("videoId")
                        if (videoId != null) {
                            val title = message.payload?.string("title") ?: ""
                            val artist = message.payload?.string("artist") ?: ""
                            val thumbnailUrl = message.payload?.string("thumbnailUrl") ?: ""
                            val durationMs = message.payload?.long("durationMs") ?: 0L

                            _incomingCommands.tryEmit(
                                JamCommand.AddToQueue(videoId, title, artist, thumbnailUrl, durationMs)
                            )
                        }
                    }
                    "SONG_REMOVED" -> {
                        val queueId = message.payload?.string("queueId") ?: return
                        _incomingCommands.tryEmit(JamCommand.RemoveQueueItem(queueId))
                    }
                    "SONG_MOVED" -> {
                        val queueId = message.payload?.string("queueId") ?: return
                        val toIndex = message.payload.int("toIndex") ?: return
                        _incomingCommands.tryEmit(JamCommand.MoveQueueItem(queueId, toIndex))
                    }
                }
            }

            "VOTE_UPDATED" -> {
                val queueId = message.payload?.string("queueId") ?: return
                val voteCount = message.payload.int("voteCount") ?: 0
                val voterIds = message.payload?.get("voterIds")?.jsonArray
                    ?.mapNotNull { it.jsonPrimitive.content }?.toSet() ?: emptySet()
                updateState { state ->
                    val updatedQueue = state.playbackState.queue.map { item ->
                        if (item.queueId == queueId) item.copy(voteCount = voteCount, voterIds = voterIds)
                        else item
                    }
                    state.copy(playbackState = state.playbackState.copy(queue = updatedQueue))
                }
            }

            "RECOMMENDATIONS_UPDATED" -> {
                val enabled = message.payload?.bool("enabled") ?: false
                val recs = message.payload?.get("recommendations")?.jsonArray?.mapNotNull { parseQueueItem(it) } ?: emptyList()
                updateState { state ->
                    val manualQueue = state.playbackState.queue.filter { !it.isRecommendation }
                    val newQueue = if (enabled) manualQueue + recs else manualQueue
                    state.copy(
                        recommendationsEnabled = enabled,
                        playbackState = state.playbackState.copy(queue = newQueue),
                    )
                }
            }

            "COMMAND" -> {
                val currentState = _sessionState.value ?: return
                when (message.command) {
                    "PLAY" -> {
                        _incomingCommands.tryEmit(JamCommand.Play)
                        updateState { it.copy(playbackState = it.playbackState.copy(isPlaying = true)) }
                    }
                    "PAUSE" -> {
                        _incomingCommands.tryEmit(JamCommand.Pause)
                        updateState { it.copy(playbackState = it.playbackState.copy(isPlaying = false)) }
                    }
                    "SEEK" -> {
                        val ms = message.payload?.long("positionMs") ?: 0L
                        _incomingCommands.tryEmit(JamCommand.Seek(ms))
                        updateState { it.copy(playbackState = it.playbackState.copy(playbackPositionMs = ms)) }
                    }
                    "SKIP" -> {
                        val dir = message.payload?.int("direction") ?: 1
                        _incomingCommands.tryEmit(JamCommand.Skip(dir))
                    }
                    "SKIP_TO" -> {
                        val index = message.payload?.int("index") ?: return
                        _incomingCommands.tryEmit(JamCommand.SkipTo(index))
                    }
                    "PLAY_NOW" -> {
                        val videoId = message.payload?.string("videoId") ?: return
                        val title = message.payload?.string("title") ?: ""
                        val artist = message.payload?.string("artist") ?: ""
                        val thumbnailUrl = message.payload?.string("thumbnailUrl") ?: ""
                        val durationMs = message.payload?.long("durationMs") ?: 0L

                        _incomingCommands.tryEmit(
                            JamCommand.PlayNow(videoId, title, artist, thumbnailUrl, durationMs)
                        )
                    }
                    "SET_SHUFFLE" -> {
                        val enabled = message.payload?.bool("enabled") ?: false
                        _incomingCommands.tryEmit(JamCommand.SetShuffle(enabled))
                        updateState { it.copy(playbackState = it.playbackState.copy(shuffle = enabled)) }
                    }
                    "SET_REPEAT" -> {
                        val modeRaw = message.payload?.string("mode") ?: "OFF"
                        val mode = try { JamRepeatMode.valueOf(modeRaw) } catch (_: Exception) { JamRepeatMode.OFF }
                        _incomingCommands.tryEmit(JamCommand.SetRepeat(mode))
                        updateState { it.copy(playbackState = it.playbackState.copy(repeatMode = mode)) }
                    }
                    "SHARE_TASTE" -> {
                        val senderId = message.userId ?: return
                        val tracks = message.payload?.get("tracks")?.jsonArray
                            ?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
                        updateState { state ->
                            state.copy(guestTastes = state.guestTastes + (senderId to tracks))
                        }
                    }
                    "UNSHARE_TASTE" -> {
                        val senderId = message.userId ?: return
                        updateState { state ->
                            state.copy(guestTastes = state.guestTastes.filterKeys { it != senderId })
                        }
                    }
                    "CHAT" -> {
                        val senderId = message.userId ?: return
                        val text = message.payload?.string("text") ?: ""
                        val timestamp = message.payload?.long("timestamp") ?: 0L
                        _chatMessages.value = _chatMessages.value + JamCommand.ChatMessage(senderId, text, timestamp)
                    }
                }
            }
        }
    }

    // ── Convenience ──────────────────────────────────────────────────────────

    private inline fun updateState(transform: (JamSessionState) -> JamSessionState) {
        _sessionState.value = _sessionState.value?.let(transform)
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  JamRepository interface
    // ─────────────────────────────────────────────────────────────────────────

    override suspend fun createSession(userId: String, name: String, imageUrl: String): Result<String> {
        localUserId = userId
        connectionJob?.cancel()
        // The server returns the roomId later via SESSION_CREATED, so we return empty/success here
        connectionJob = scope.launch {
            jamClient.connect(
                getRoomId = { _sessionState.value?.roomId },
                userId = userId,
                name = name,
                imageUrl = imageUrl
            )
        }
        return Result.success("") 
    }

    override suspend fun joinSession(roomId: String, userId: String, name: String, imageUrl: String): Result<Unit> {
        localUserId = userId
        connectionJob?.cancel()
        connectionJob = scope.launch {
            jamClient.connect(
                getRoomId = { roomId },
                userId = userId,
                name = name,
                imageUrl = imageUrl
            )
        }
    return Result.success(Unit)
}

    override suspend fun leaveSession() {
        connectionJob?.cancel()
        jamClient.disconnect()
        _sessionState.value = null
        _chatMessages.value = emptyList()
    }

    override suspend fun syncState(state: JamPlaybackState) {
        jamClient.sendRaw(
            JamMessage(
                type = "SYNC_STATE",
                payload = buildStatePayload(state),
            )
        )
    }

    override suspend fun updatePermissions(permissions: JamPermissions) {
        jamClient.sendRaw(
            JamMessage(
                type = "UPDATE_PERMISSIONS",
                payload = JsonObject(
                    mapOf(
                        "permissions" to JsonObject(
                            mapOf(
                                "allowAddSongs" to JsonPrimitive(permissions.allowAddSongs),
                                "allowRemoveSongs" to JsonPrimitive(permissions.allowRemoveSongs),
                                "allowReorder" to JsonPrimitive(permissions.allowReorder),
                                "allowPause" to JsonPrimitive(permissions.allowPause),
                                "allowSkip" to JsonPrimitive(permissions.allowSkip),
                                "allowSeek" to JsonPrimitive(permissions.allowSeek),
                                "allowVoting" to JsonPrimitive(permissions.allowVoting),
                            )
                        )
                    )
                )
            )
        )
    }

    override suspend fun sendCommand(command: JamCommand) {
        val type = commandType(command)
        val payload = commandPayload(command)

        // Rely on the server broadcast for ChatMessage so it doesn't duplicate
        // (No local echo)
        jamClient.sendCommand(type, payload)
    }

    // ── Serialize commands ────────────────────────────────────────────────────

    private fun commandType(command: JamCommand) = when (command) {
        is JamCommand.Play -> "PLAY"
        is JamCommand.Pause -> "PAUSE"
        is JamCommand.Seek -> "SEEK"
        is JamCommand.Skip -> "SKIP"
        is JamCommand.SkipTo -> "SKIP_TO"
        is JamCommand.AddToQueue -> "ADD_TO_QUEUE"
        is JamCommand.RemoveQueueItem -> "REMOVE_QUEUE_ITEM"
        is JamCommand.RemoveFromQueue -> "REMOVE_QUEUE_ITEM"
        is JamCommand.MoveQueueItem -> "MOVE_QUEUE_ITEM"
        is JamCommand.PlayNow -> "PLAY_NOW"
        is JamCommand.Vote -> "VOTE"
        is JamCommand.EnableRecommendations -> "ENABLE_RECOMMENDATIONS"
        is JamCommand.RefreshRecommendations -> "REFRESH_RECOMMENDATIONS"
        is JamCommand.SetShuffle -> "SET_SHUFFLE"
        is JamCommand.SetRepeat -> "SET_REPEAT"
        is JamCommand.SyncState -> "SYNC_STATE"
        is JamCommand.ShareTaste -> "SHARE_TASTE"
        is JamCommand.UnshareTaste -> "UNSHARE_TASTE"
        is JamCommand.ChatMessage -> "CHAT"
        is JamCommand.Ping -> "PING"
    }

    private fun commandPayload(command: JamCommand): JsonObject? = when (command) {
        is JamCommand.Seek ->
            JsonObject(mapOf("positionMs" to JsonPrimitive(command.positionMs)))

        is JamCommand.AddToQueue ->
            JsonObject(mapOf(
                "videoId" to JsonPrimitive(command.videoId),
                "title" to JsonPrimitive(command.title),
                "artist" to JsonPrimitive(command.artist),
                "thumbnailUrl" to JsonPrimitive(command.thumbnailUrl),
                "durationMs" to JsonPrimitive(command.durationMs)
            ))

        is JamCommand.Skip ->
            JsonObject(mapOf("direction" to JsonPrimitive(command.direction)))

        is JamCommand.SkipTo ->
            JsonObject(mapOf("index" to JsonPrimitive(command.index)))

        is JamCommand.RemoveQueueItem ->
            JsonObject(mapOf("queueId" to JsonPrimitive(command.queueId)))

        is JamCommand.RemoveFromQueue ->
            JsonObject(mapOf("index" to JsonPrimitive(command.index)))

        is JamCommand.MoveQueueItem ->
            JsonObject(mapOf(
                "queueId" to JsonPrimitive(command.queueId),
                "toIndex" to JsonPrimitive(command.toIndex),
            ))

        is JamCommand.PlayNow ->
            JsonObject(mapOf(
                "videoId" to JsonPrimitive(command.videoId),
                "title" to JsonPrimitive(command.title),
                "artist" to JsonPrimitive(command.artist),
                "thumbnailUrl" to JsonPrimitive(command.thumbnailUrl),
                "durationMs" to JsonPrimitive(command.durationMs)
            ))

        is JamCommand.Vote ->
            JsonObject(mapOf("queueId" to JsonPrimitive(command.queueId)))

        is JamCommand.EnableRecommendations ->
            JsonObject(mapOf("enabled" to JsonPrimitive(command.enabled)))

        is JamCommand.SetShuffle ->
            JsonObject(mapOf("enabled" to JsonPrimitive(command.enabled)))

        is JamCommand.SetRepeat ->
            JsonObject(mapOf("mode" to JsonPrimitive(command.mode.name)))

        is JamCommand.ShareTaste ->
            JsonObject(mapOf("tracks" to JsonArray(command.tracks.map { 
                JsonObject(mapOf(
                    "videoId" to JsonPrimitive(it.videoId),
                    "title" to JsonPrimitive(it.title),
                    "artist" to JsonPrimitive(it.artist),
                    "thumbnailUrl" to JsonPrimitive(it.thumbnailUrl ?: ""),
                    "durationMs" to JsonPrimitive(it.durationMs)
                ))
            })))

        is JamCommand.ChatMessage ->
            JsonObject(mapOf(
                "text" to JsonPrimitive(command.text),
                "timestamp" to JsonPrimitive(command.timestamp),
            ))

        is JamCommand.SyncState -> buildStatePayload(command.state)

        else -> null
    }

    private fun buildStatePayload(state: JamPlaybackState): JsonObject = JsonObject(
        mapOf(
            "state" to JsonObject(
                mapOf(
                    "currentSongId" to JsonPrimitive(state.currentSongId),
                    "isPlaying" to JsonPrimitive(state.isPlaying),
                    "playbackPositionMs" to JsonPrimitive(state.playbackPositionMs),
                    "shuffle" to JsonPrimitive(state.shuffle),
                    "repeatMode" to JsonPrimitive(state.repeatMode.name),
                    "serverTimestampMs" to JsonPrimitive(getTimeMillis()),
                )
            )
        )
    )
}
