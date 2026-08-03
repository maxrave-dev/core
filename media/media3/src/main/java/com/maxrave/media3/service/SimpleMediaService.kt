package com.maxrave.media3.service

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.content.getSystemService
import androidx.media3.common.Player
import androidx.media3.common.Player.COMMAND_GET_TIMELINE
import androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS
import androidx.media3.common.Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.media3.ui.DefaultMediaDescriptionAdapter
import androidx.media3.ui.PlayerNotificationManager
import com.google.common.util.concurrent.MoreExecutors
import com.maxrave.common.MEDIA_CUSTOM_COMMAND
import com.maxrave.common.MEDIA_NOTIFICATION
import com.maxrave.domain.data.player.GenericCommandButton
import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.logger.Logger
import com.maxrave.media3.R
import com.maxrave.media3.extension.toMediaButtonPreferences
import com.maxrave.media3.utils.CoilBitmapLoader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.seconds

@UnstableApi
internal class SimpleMediaService :
    MediaLibraryService(),
    KoinComponent {
    private val coroutineScope by inject<CoroutineScope>(named(com.maxrave.common.Config.SERVICE_SCOPE))
    // Session-level player from DI: the ForwardingPlayer wrapped with Cast support in the
    // full build (plain ForwardingPlayer in the FOSS build).
    private val player: Player by inject<Player>(qualifier = named(com.maxrave.common.Config.MAIN_PLAYER))
    private val coilBitmapLoader: CoilBitmapLoader by inject<CoilBitmapLoader>()

    private var mediaSession: MediaLibrarySession? = null

    private val simpleMediaSessionCallback: MediaLibrarySession.Callback by inject<MediaLibrarySession.Callback>()

    private val simpleMediaServiceHandler: MediaPlayerHandler by inject<MediaPlayerHandler>()
    private val dataStoreManager: DataStoreManager by inject<DataStoreManager>()

    private val binder = MusicBinder()

    private lateinit var playerNotificationManager: PlayerNotificationManager

    inner class MusicBinder : Binder() {
        val service: SimpleMediaService
            get() = this@SimpleMediaService

        fun setActivitySession(
            context: Context,
            activity: Class<out Activity>,
        ) {
            mediaSession?.setSessionActivity(
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, activity),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        Logger.w("Service", "Simple Media Service Bound")
        return super.onBind(intent) ?: binder
    }

    @UnstableApi
    override fun onCreate() {
        super.onCreate()
        Logger.w("Service", "Simple Media Service Created")

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider(
                this,
                { MEDIA_NOTIFICATION.NOTIFICATION_ID },
                MEDIA_NOTIFICATION.NOTIFICATION_CHANNEL_ID,
                R.string.notification_channel_name,
            ).apply {
                setSmallIcon(R.drawable.mono)
            },
        )

        if (mediaSession == null) {
            mediaSession =
                provideMediaLibrarySession(
                    this,
                    player,
                    simpleMediaSessionCallback,
                )
        }

        fun currentCommandButtons(): List<GenericCommandButton> {
            val control = simpleMediaServiceHandler.controlState.value
            return listOf(
                GenericCommandButton.Like(control.isLiked),
                GenericCommandButton.Shuffle(isShuffled = control.isShuffle),
                GenericCommandButton.Repeat(repeatState = control.repeatState),
                GenericCommandButton.Radio,
            )
        }

        fun isCarOrPlatformController(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): Boolean =
            session.isMediaNotificationController(controller) ||
                session.isAutoCompanionController(controller) ||
                session.isAutomotiveController(controller) ||
                controller.packageName == "com.google.android.projection.gearhead" ||
                controller.packageName.contains("projection", ignoreCase = true) ||
                controller.packageName.contains("gearhead", ignoreCase = true)

        fun sessionCommandsWithCustoms() =
            MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS
                .buildUpon()
                .add(SessionCommand(MEDIA_CUSTOM_COMMAND.LIKE, android.os.Bundle()))
                .add(SessionCommand(MEDIA_CUSTOM_COMMAND.REPEAT, android.os.Bundle()))
                .add(SessionCommand(MEDIA_CUSTOM_COMMAND.RADIO, android.os.Bundle()))
                .add(SessionCommand(MEDIA_CUSTOM_COMMAND.SHUFFLE, android.os.Bundle()))
                .add(SessionCommand(MEDIA_CUSTOM_COMMAND.PREVIOUS, android.os.Bundle()))
                .add(SessionCommand(MEDIA_CUSTOM_COMMAND.GET_PLATFORM_TOKEN, android.os.Bundle()))
                .build()

        fun applyTransportLayout(list: List<GenericCommandButton> = currentCommandButtons()) {
            val session = mediaSession ?: return
            val aaLikeEnabled =
                runBlocking {
                    dataStoreManager.androidAutoLikeInsteadOfPrevious.first() == DataStoreManager.TRUE
                }
            // AA now-playing shares the platform/media-notification session with the phone
            // notification, so apply the AA layout session-wide when the setting is on.
            session.setMediaButtonPreferences(
                list.toMediaButtonPreferences(this, aaLikeEnabled),
            )
            // onConnect may have withheld SEEK_TO_PREVIOUS while the setting was on; restore
            // or re-withhold it when the toggle changes so system Prev comes back when off.
            val sessionCommands = sessionCommandsWithCustoms()
            for (controller in session.connectedControllers) {
                val playerCommandsBuilder =
                    Player.Commands
                        .Builder()
                        .addAllCommands()
                        .remove(COMMAND_GET_TIMELINE)
                if (aaLikeEnabled && isCarOrPlatformController(session, controller)) {
                    playerCommandsBuilder
                        .remove(COMMAND_SEEK_TO_PREVIOUS)
                        .remove(COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                }
                session.setAvailableCommands(
                    controller,
                    sessionCommands,
                    playerCommandsBuilder.build(),
                )
            }
            Logger.w(
                "Service",
                "applyTransportLayout aaLike=$aaLikeEnabled controllers=${session.connectedControllers.size}",
            )
        }

        simpleMediaServiceHandler.onUpdateNotification = { list ->
            applyTransportLayout(list)
        }

        // Re-apply layout/commands when the setting toggles (no reconnect required).
        coroutineScope.launch {
            dataStoreManager.androidAutoLikeInsteadOfPrevious
                .distinctUntilChanged()
                .collect {
                    applyTransportLayout()
                }
        }

        val sessionToken = SessionToken(this, ComponentName(this, SimpleMediaService::class.java))
        val controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture.addListener({ controllerFuture.get() }, MoreExecutors.directExecutor())

        if (runBlocking { dataStoreManager.keepServiceAlive.first() == DataStoreManager.TRUE }) {
            val notificationManager = getSystemService<NotificationManager>()
            notificationManager?.run {
                createNotificationChannel(
                    NotificationChannel(
                        "media_playback_channel",
                        "Now playing",
                        NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        setSound(null, null)
                        enableLights(false)
                        enableVibration(false)
                    },
                )
            }
            playerNotificationManager =
                PlayerNotificationManager
                    .Builder(this, 2026, "media_playback_channel")
                    .setNotificationListener(
                        object : PlayerNotificationManager.NotificationListener {
                            override fun onNotificationPosted(
                                notificationId: Int,
                                notification: Notification,
                                ongoing: Boolean,
                            ) {
                                fun startFg() {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                                        startForeground(notificationId, notification, FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                                    } else {
                                        startForeground(notificationId, notification)
                                    }
                                }
                                coroutineScope.launch {
                                    while (coroutineScope.isActive) {
                                        startFg()
                                        delay(30.seconds)
                                    }
                                }
                            }
                        },
                    ).setMediaDescriptionAdapter(DefaultMediaDescriptionAdapter(mediaSession?.sessionActivity))
                    .build()
            playerNotificationManager.setPlayer(player)
            playerNotificationManager.setSmallIcon(R.drawable.mono)
            mediaSession?.platformToken?.let { playerNotificationManager.setMediaSessionToken(it) }
        }

        simpleMediaServiceHandler.onUpdateNotification = { list ->
            applyTransportLayout(list)
        }
    }

    @UnstableApi
    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        Logger.w("Service", "Simple Media Service Received Action: ${intent?.action}")
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = mediaSession

    @UnstableApi
    override fun onUpdateNotification(
        session: MediaSession,
        startInForegroundRequired: Boolean,
    ) {
        super.onUpdateNotification(session, startInForegroundRequired)
    }

    @UnstableApi
    fun release() {
        Logger.w("Service", "Starting release process")
        runBlocking {
            try {
                // Release MediaSession (don't release player - CrossfadeExoPlayerAdapter manages it)
                mediaSession?.run {
                    this.player.pause()
                    this.player.playWhenReady = false
                    // Don't call this.player.release() - CrossfadeExoPlayerAdapter manages player lifecycle
                    this.release()
                }
                // Release handler (contains coroutines and jobs, which also releases the adapter)
                simpleMediaServiceHandler.release()
                mediaSession = null
                Logger.w("Service", "Simple Media Service Released")
            } catch (e: Exception) {
                Logger.e("Service", "Error during release")
            }
        }
    }

    @UnstableApi
    override fun onDestroy() {
        super.onDestroy()
        Logger.w("Service", "Simple Media Service Destroyed")
        if (simpleMediaServiceHandler.shouldReleaseOnTaskRemoved()) {
            release()
        }
    }

    override fun onTrimMemory(level: Int) {
        Logger.w("Service", "Simple Media Service Trim Memory Level: $level")
        simpleMediaServiceHandler.mayBeSaveRecentSong()
    }

    @UnstableApi
    override fun onTaskRemoved(rootIntent: Intent?) {
        Logger.w("Service", "Simple Media Service Task Removed")
        if (simpleMediaServiceHandler.shouldReleaseOnTaskRemoved()) {
            release()
            super.onTaskRemoved(rootIntent)
            exitProcess(0)
        }
    }

    // Can't inject by Koin because it depend on service
    @UnstableApi
    private fun provideMediaLibrarySession(
        service: MediaLibraryService,
        player: Player,
        callback: MediaLibrarySession.Callback,
    ): MediaLibrarySession =
        MediaLibrarySession
            .Builder(
                service,
                player,
                callback,
            ).setId(this.javaClass.name)
            .setBitmapLoader(coilBitmapLoader)
            .build()

    private fun isAppInForeground(): Boolean {
        val appProcessInfo = RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(appProcessInfo)
        return appProcessInfo.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
}