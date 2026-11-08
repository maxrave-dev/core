package com.maxrave.media3.carapp

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.support.v4.media.session.MediaSessionCompat
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.media.MediaPlaybackManager
import androidx.car.app.validation.HostValidator
import androidx.core.content.ContextCompat
import androidx.core.os.BundleCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaBrowser
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.maxrave.common.MEDIA_CUSTOM_COMMAND
import com.maxrave.logger.Logger
import com.maxrave.media3.service.SimpleMediaService

/**
 * Entry point for the Android Auto templated media experience (Car App
 * Library). Playback keeps flowing through [SimpleMediaService]'s
 * MediaSession as before; this service only contributes the template UI
 * (header + custom queue screen) on top of it.
 */
@UnstableApi
internal class SimpMusicCarAppService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator
                .Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = SimpMusicCarSession()
}

@UnstableApi
internal class SimpMusicCarSession : Session() {
    private var browserFuture: ListenableFuture<MediaBrowser>? = null

    init {
        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onCreate(owner: LifecycleOwner) {
                    connectAndRegisterPlaybackToken()
                }

                override fun onDestroy(owner: LifecycleOwner) {
                    browserFuture?.let { MediaController.releaseFuture(it) }
                    browserFuture = null
                }
            },
        )
    }

    override fun onCreateScreen(intent: Intent): Screen {
        // Library tab root sits under the playback screen so BACK lands on
        // browse, matching the classic media surface
        carContext.getCarService(ScreenManager::class.java).push(
            LibraryTabCarScreen(carContext, ::ensureBrowser),
        )
        return NowPlayingCarScreen(carContext)
    }

    override fun onNewIntent(intent: Intent) {
        Logger.w(TAG, "onNewIntent action=${intent.action}")
        // System intents asking the app to surface its playback screen
        if (intent.action == ACTION_SHOW_MEDIA_PLAYBACK || intent.action == ACTION_MEDIA_SHOW_PLAYBACK_VIEW) {
            val screenManager = carContext.getCarService(ScreenManager::class.java)
            if (screenManager.top !is NowPlayingCarScreen) {
                // Pop first: the browse stack may already sit at the host's 5-screen cap
                screenManager.popToRoot()
                screenManager.push(NowPlayingCarScreen(carContext))
            }
        }
    }

    /**
     * Media3 handshake from the templated-media-app guide: connecting a
     * browser also starts [SimpleMediaService] when needed, then a custom
     * session command returns the platform token, which the car host needs
     * (compat-wrapped) to render playback state and controls.
     */
    private fun ensureBrowser(): ListenableFuture<MediaBrowser> =
        browserFuture
            ?: MediaBrowser
                .Builder(
                    carContext,
                    SessionToken(carContext, ComponentName(carContext, SimpleMediaService::class.java)),
                ).setListener(
                    object : MediaBrowser.Listener {
                        override fun onDisconnected(controller: MediaController) {
                            // Media service died mid-drive: rebuild the browser and hand
                            // the host a fresh token, else it keeps rendering a stale one
                            browserFuture?.let { MediaController.releaseFuture(it) }
                            browserFuture = null
                            if (lifecycle.currentState.isAtLeast(Lifecycle.State.CREATED)) {
                                connectAndRegisterPlaybackToken()
                            }
                        }
                    },
                ).buildAsync()
                .also { browserFuture = it }

    private fun connectAndRegisterPlaybackToken() {
        val mainExecutor = ContextCompat.getMainExecutor(carContext)
        val future = ensureBrowser()
        future.addListener({
            runCatching {
                val browser = future.get()
                val resultFuture =
                    browser.sendCustomCommand(
                        SessionCommand(MEDIA_CUSTOM_COMMAND.GET_PLATFORM_TOKEN, Bundle.EMPTY),
                        Bundle.EMPTY,
                    )
                resultFuture.addListener({
                    runCatching {
                        val result = resultFuture.get()
                        val platformToken =
                            BundleCompat.getParcelable(
                                result.extras,
                                MEDIA_CUSTOM_COMMAND.KEY_PLATFORM_TOKEN,
                                android.media.session.MediaSession.Token::class.java,
                            )
                        if (result.resultCode == SessionResult.RESULT_SUCCESS && platformToken != null) {
                            (carContext.getCarService(CarContext.MEDIA_PLAYBACK_SERVICE) as MediaPlaybackManager)
                                .registerMediaPlaybackToken(MediaSessionCompat.Token.fromToken(platformToken))
                        } else {
                            Logger.e(TAG, "Platform token missing from ${MEDIA_CUSTOM_COMMAND.GET_PLATFORM_TOKEN} result")
                        }
                    }.onFailure {
                        Logger.e(TAG, "registerMediaPlaybackToken failed: ${it.message}")
                    }
                }, mainExecutor)
            }.onFailure {
                Logger.e(TAG, "MediaBrowser connection failed: ${it.message}")
            }
        }, mainExecutor)
    }

    private companion object {
        private const val TAG = "SimpMusicCarSession"

        // Docs-defined action for templated media apps; no library constant as of car-app 1.9.0-alpha01
        private const val ACTION_SHOW_MEDIA_PLAYBACK = "androidx.car.app.media.action.SHOW_MEDIA_PLAYBACK"

        // Actual action observed in logcat when the minimized control panel is tapped —
        // the (beta) host sends this instead of the documented one
        private const val ACTION_MEDIA_SHOW_PLAYBACK_VIEW = "MEDIA_SHOW_PLAYBACK_VIEW"
    }
}