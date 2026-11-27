package com.maxrave.media3.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.annotation.StringRes
import androidx.core.app.NotificationCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.maxrave.common.MEDIA_NOTIFICATION
import com.maxrave.logger.Logger
import com.maxrave.media3.R

/**
 * MediaNotification.Provider implementation supporting OEM Dynamic Island / Capsule / Live Alerts
 * across Android devices:
 * - Xiaomi / Poco / Redmi HyperOS & MIUI: Super Island & Focus Notification (焦点通知)
 * - Oppo / OnePlus / Realme (ColorOS / OxygenOS / Realme UI): Pantanal framework, Fluid Cloud (流体云),
 *   Live Alerts (实时提醒), and Mini Capsule (迷你胶囊)
 * - Honor MagicOS: Smart Capsule & Magic Capsule (灵动胶囊)
 * - Vivo / iQOO OriginOS & FuntouchOS: Origin Island / Atomic Island (原子岛)
 * - Transsion HiOS / XOS / itelOS (Infinix / Tecno / Itel): Dynamic Bar & Dynamic Port
 * - Samsung One UI: Now Bar & live lockscreen / Always-On Display playback activity
 * - Google Pixel & modern Android: Ongoing live notification with prompt foreground visibility
 */
@UnstableApi
internal class SimpMediaNotificationProvider(
    private val context: Context,
    private val notificationIdProvider: () -> Int = { MEDIA_NOTIFICATION.NOTIFICATION_ID },
    private val channelId: String = MEDIA_NOTIFICATION.NOTIFICATION_CHANNEL_ID,
    @StringRes private val channelNameResourceId: Int = R.string.notification_channel_name,
) : MediaNotification.Provider {

    private val defaultProvider =
        DefaultMediaNotificationProvider(
            context,
            { notificationIdProvider() },
            channelId,
            channelNameResourceId,
        ).apply {
            setSmallIcon(R.drawable.mono)
        }

    init {
        ensureProminentSilentChannel(context, channelId, channelNameResourceId)
    }

    override fun createNotification(
        session: MediaSession,
        customLayout: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification {
        val baseNotification =
            defaultProvider.createNotification(
                session,
                customLayout,
                actionFactory,
                onNotificationChangedCallback,
            )

        val notification = baseNotification.notification
        val player = session.player
        val isPlaying = player.playWhenReady &&
            player.playbackState != Player.STATE_IDLE &&
            player.playbackState != Player.STATE_ENDED

        val metadata = player.mediaMetadata
        val title = metadata.title?.toString() ?: ""
        val artist = metadata.artist?.toString() ?: ""

        applyOemCapsuleProperties(
            notification = notification,
            isPlaying = isPlaying,
            artist = artist,
            title = title,
        )

        // Ensure tap intent opens the player activity if unset
        if (notification.contentIntent == null && session.sessionActivity != null) {
            notification.contentIntent = session.sessionActivity
        }

        return baseNotification
    }

    override fun handleCustomCommand(
        session: MediaSession,
        action: String,
        extras: Bundle,
    ): Boolean = defaultProvider.handleCustomCommand(session, action, extras)

    override fun getNotificationChannelInfo(): MediaNotification.Provider.NotificationChannelInfo =
        MediaNotification.Provider.NotificationChannelInfo(
            channelId,
            context.getString(channelNameResourceId),
        )

    companion object {
        /**
         * Creates or upgrades the playback notification channel to IMPORTANCE_DEFAULT with sound
         * and vibration disabled so that OEM dynamic island engines (which suppress IMPORTANCE_LOW)
         * actively show status bar capsules and islands without any intrusive chimes on track transitions.
         */
        fun ensureProminentSilentChannel(
            context: Context,
            channelId: String,
            @StringRes channelNameResourceId: Int,
        ) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val notificationManager =
                    context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                        ?: return

                // Delete obsolete low-importance channel so notification settings remain clean
                val legacyChannelId = MEDIA_NOTIFICATION.LEGACY_NOTIFICATION_CHANNEL_ID
                if (channelId != legacyChannelId) {
                    try {
                        notificationManager.deleteNotificationChannel(legacyChannelId)
                    } catch (e: Exception) {
                        Logger.e("NotificationProvider", "Failed to clean legacy channel: ${e.message}")
                    }
                }

                val existing = notificationManager.getNotificationChannel(channelId)
                if (existing == null || existing.importance < NotificationManager.IMPORTANCE_DEFAULT) {
                    if (existing != null) {
                        try {
                            notificationManager.deleteNotificationChannel(channelId)
                        } catch (e: Exception) {
                            Logger.e("NotificationProvider", "Failed to recreate channel: ${e.message}")
                        }
                    }
                    val channel =
                        NotificationChannel(
                            channelId,
                            context.getString(channelNameResourceId),
                            NotificationManager.IMPORTANCE_DEFAULT,
                        ).apply {
                            description = "Playback controls and live status"
                            setSound(null, null)
                            enableVibration(false)
                            enableLights(false)
                            setShowBadge(false)
                            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                        }
                    notificationManager.createNotificationChannel(channel)
                }
            }
        }

        /**
         * Injects required flags, category, visibility, and OEM-specific metadata into the Notification.
         */
        fun applyOemCapsuleProperties(
            notification: Notification,
            isPlaying: Boolean,
            artist: String = "",
            title: String = "",
        ) {
            // 1. Mandatory category and public visibility for media playback
            notification.category = NotificationCompat.CATEGORY_TRANSPORT
            notification.visibility = NotificationCompat.VISIBILITY_PUBLIC

            // 2. Ongoing flag management:
            // Crucial for OEM SystemUI capsules (HyperOS, ColorOS/OxygenOS Pantanal, MagicOS, Dynamic Bar).
            // When actively playing, FLAG_ONGOING_EVENT promotes the notification to the status bar capsule.
            // When paused, we clear it so the user can swipe away the notification if desired.
            if (isPlaying) {
                notification.flags = notification.flags or Notification.FLAG_ONGOING_EVENT
            } else {
                notification.flags = notification.flags and Notification.FLAG_ONGOING_EVENT.inv()
            }

            // 3. Immediate foreground service display on Android 12+ (no 10-second delay)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    val field = Notification::class.java.getDeclaredField("mFgsDeferBehavior")
                    field.isAccessible = true
                    field.setInt(notification, 2) // FOREGROUND_SERVICE_IMMEDIATE
                } catch (ignored: Throwable) {
                    // Handled natively by Media3 builder
                }
            }

            // 4. Populate OEM Dynamic Island & Live Notification extras
            val extras = notification.extras ?: Bundle().also { notification.extras = it }
            val oemExtras = buildOemCapsuleExtrasMap(isPlaying, artist, title)
            for ((key, value) in oemExtras) {
                when (value) {
                    is Boolean -> extras.putBoolean(key, value)
                    is Int -> extras.putInt(key, value)
                    is String -> extras.putString(key, value)
                }
            }
        }

        /**
         * Pure map of OEM capsule and dynamic island extra key-value pairs.
         * Separated from Android framework objects so it can be tested in standard host JVM unit tests.
         */
        fun buildOemCapsuleExtrasMap(
            isPlaying: Boolean,
            artist: String = "",
            title: String = "",
        ): Map<String, Any> {
            val map = mutableMapOf<String, Any>()

            // Standard Android & Google Live Notification / Ongoing Activity
            map["android.ongoing"] = isPlaying
            map["android.extra.ongoing"] = isPlaying
            map["android.requestPromotedOngoing"] = isPlaying
            if (artist.isNotEmpty()) {
                map[NotificationCompat.EXTRA_SUB_TEXT] = artist
            }

            // Xiaomi HyperOS & MIUI: Super Island & Focus Notification (焦点通知)
            map["miui.showFocusNotification"] = isPlaying
            map["showFocusNotification"] = isPlaying
            map["com.miui.focus_notification"] = isPlaying
            map["miui.showFocus"] = isPlaying
            map["miui.enableFloatWindow"] = isPlaying
            map["miui.focusNotificationType"] = 1 // 1: media / ongoing
            map["miui.focusType"] = 1

            // Oppo, OnePlus, Realme: ColorOS / OxygenOS / Realme UI Pantanal, Fluid Cloud (流体云),
            // Live Alerts (实时提醒), and Mini Capsule (迷你胶囊)
            map["oplus.live_alert"] = isPlaying
            map["coloros.live_alert"] = isPlaying
            map["pantanal.support"] = true
            map["oplus.capsule"] = isPlaying
            map["oplus.capsule_type"] = 1
            map["com.oplus.pantanal.type"] = 1
            if (artist.isNotEmpty()) {
                map["oplus.extra.subText"] = artist
                map["coloros.extra.subText"] = artist
            }

            // Honor MagicOS: Smart Capsule & Magic Capsule (灵动胶囊)
            map["honor.smart_capsule"] = isPlaying
            map["com.hihonor.systemui.smart_capsule"] = isPlaying
            map["com.honor.systemui.capsule"] = isPlaying
            map["honor.capsule_type"] = 1

            // Transsion (Infinix, Tecno, Itel): Dynamic Bar & Dynamic Port
            map["transsion.dynamic_bar"] = isPlaying
            map["com.transsion.systemui.dynamic_bar"] = isPlaying
            map["dynamic_bar_enable"] = isPlaying

            // Vivo & iQOO: Origin Island & Atomic Island (原子岛)
            map["vivo.summary"] = true
            map["vivo.island"] = isPlaying
            map["com.vivo.notification.island"] = isPlaying

            // Samsung One UI: Now Bar & Live Activity
            map["com.samsung.android.nowbar"] = true

            return map
        }
    }
}
