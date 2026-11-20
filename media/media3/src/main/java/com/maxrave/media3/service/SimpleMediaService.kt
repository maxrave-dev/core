package com.maxrave.media3.service

import android.app.Activity
import android.app.ActivityManager
import android.app.ActivityManager.RunningAppProcessInfo
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.maxrave.common.MEDIA_NOTIFICATION
import com.maxrave.domain.mediaservice.handler.MediaPlayerHandler
import com.maxrave.logger.Logger
import com.maxrave.media3.R
import com.maxrave.media3.extension.toCommandButton
import com.maxrave.media3.utils.CoilBitmapLoader
import com.maxrave.media3.utils.sizeLimitedForSession
import kotlinx.coroutines.runBlocking
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import kotlin.system.exitProcess

@UnstableApi
internal class SimpleMediaService :
    MediaLibraryService(),
    KoinComponent {
    // Session-level player from DI: the ForwardingPlayer wrapped with Cast support in the
    // full build (plain ForwardingPlayer in the FOSS build).
    private val player: Player by inject<Player>(qualifier = named(com.maxrave.common.Config.MAIN_PLAYER))
    private val coilBitmapLoader: CoilBitmapLoader by inject<CoilBitmapLoader>()

    private var mediaSession: MediaLibrarySession? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null

    private val simpleMediaSessionCallback: MediaLibrarySession.Callback by inject<MediaLibrarySession.Callback>()

    private val simpleMediaServiceHandler: MediaPlayerHandler by inject<MediaPlayerHandler>()
    private val castHandoffManager: com.maxrave.media3.cast.CastHandoffManager by inject()

    private val binder = MusicBinder()

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
        castHandoffManager

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

        simpleMediaServiceHandler.onUpdateNotification = { list ->
            val commandButtonList = list.map { it.toCommandButton(this) }
            mediaSession?.setMediaButtonPreferences(
                commandButtonList,
            )
        }

        val sessionToken = SessionToken(this, ComponentName(this, SimpleMediaService::class.java))
        val future = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener({
            try {
                future.get()
            } catch (e: Exception) {
                Logger.w("Service", "Warmup MediaController connection failed: ${e.message}")
            }
        }, MoreExecutors.directExecutor())
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
        try {
            super.onUpdateNotification(session, startInForegroundRequired)
        } catch (e: Exception) {
            // The framework's notification provider performs the foreground promotion;
            // if the system refuses it (Android 12+ restrictions on a cold start via a
            // widget or a headset button), the exception used to escape the service
            // uncaught. Log it instead of crashing the playback service over it.
            Logger.e("Service", "onUpdateNotification failed", e)
        }
    }

    private fun releaseControllerFuture() {
        controllerFuture?.let { future ->
            try {
                MediaController.releaseFuture(future)
            } catch (e: Exception) {
                Logger.w("Service", "Error releasing warmup MediaController: ${e.message}")
            }
        }
        controllerFuture = null
    }

    @UnstableApi
    fun release() {
        Logger.w("Service", "Starting release process")
        simpleMediaServiceHandler.onUpdateNotification = {}
        releaseControllerFuture()
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
                Logger.w("Service", "Simple Media Service Released")
            } catch (e: Exception) {
                Logger.e("Service", "Error during release", e)
            } finally {
                mediaSession = null
            }
        }
    }

    @UnstableApi
    override fun onDestroy() {
        super.onDestroy()
        Logger.w("Service", "Simple Media Service Destroyed")
        simpleMediaServiceHandler.onUpdateNotification = {}
        releaseControllerFuture()
        if (simpleMediaServiceHandler.shouldReleaseOnTaskRemoved()) {
            release()
        } else {
            try {
                mediaSession?.release()
            } catch (e: Exception) {
                Logger.w("Service", "Error releasing MediaSession in onDestroy: ${e.message}")
            } finally {
                mediaSession = null
            }
        }
    }

    override fun onTrimMemory(level: Int) {
        Logger.w("Service", "Simple Media Service Trim Memory Level: $level")
        simpleMediaServiceHandler.mayBeSaveRecentSong()
        simpleMediaServiceHandler.onTrimMemory(level)
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
            // Capped at the platform's own artwork limit so the framework never rescales the shared
            // bitmap in setMetadata — see sizeLimitedForSession (#2500). Falls back to the plain
            // loader if the limit cannot be read.
            .setBitmapLoader(sizeLimitedForSession(service, coilBitmapLoader))
            // Tapping the media notification opens whatever the session activity points at, and the
            // only other place that sets it is MainActivity's bind. A service started without that
            // bind — a headset/Bluetooth play, Android Auto, a widget, a restart after the process
            // was killed — would otherwise post a notification that opens nothing. The launcher
            // activity is resolved at runtime, so this module never has to name MainActivity.
            .apply {
                service.packageManager.getLaunchIntentForPackage(service.packageName)?.let { launch ->
                    setSessionActivity(
                        PendingIntent.getActivity(service, 0, launch, PendingIntent.FLAG_IMMUTABLE),
                    )
                }
            }
            .build()

    private fun isAppInForeground(): Boolean {
        val appProcessInfo = RunningAppProcessInfo()
        ActivityManager.getMyMemoryState(appProcessInfo)
        return appProcessInfo.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    }
}