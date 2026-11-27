package com.maxrave.media3.service

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class SimpMediaNotificationProviderTest {

    @Test
    fun testBuildOemCapsuleExtrasMap_whenPlaying_setsAllOemCapsuleExtrasAndOngoingStatus() {
        val extras = SimpMediaNotificationProvider.buildOemCapsuleExtrasMap(
            isPlaying = true,
            artist = "Arijit Singh",
            title = "Tum Hi Ho",
        )

        // 1. Android & Google Ongoing Activity / Live Notification
        assertEquals(true, extras["android.ongoing"])
        assertEquals(true, extras["android.extra.ongoing"])
        assertEquals(true, extras["android.requestPromotedOngoing"])
        assertEquals("Arijit Singh", extras["android.subText"])

        // 2. Xiaomi Super Island & Focus Notification (焦点通知)
        assertEquals(true, extras["miui.showFocusNotification"])
        assertEquals(true, extras["showFocusNotification"])
        assertEquals(true, extras["com.miui.focus_notification"])
        assertEquals(true, extras["miui.showFocus"])
        assertEquals(true, extras["miui.enableFloatWindow"])
        assertEquals(1, extras["miui.focusNotificationType"])
        assertEquals(1, extras["miui.focusType"])

        // 3. Oppo, OnePlus, Realme (Pantanal / Fluid Cloud / Live Alerts / Mini Capsule)
        assertEquals(true, extras["oplus.live_alert"])
        assertEquals(true, extras["coloros.live_alert"])
        assertEquals(true, extras["pantanal.support"])
        assertEquals(true, extras["oplus.capsule"])
        assertEquals(1, extras["oplus.capsule_type"])
        assertEquals(1, extras["com.oplus.pantanal.type"])
        assertEquals("Arijit Singh", extras["oplus.extra.subText"])
        assertEquals("Arijit Singh", extras["coloros.extra.subText"])

        // 4. Honor Smart Capsule & Magic Capsule (灵动胶囊)
        assertEquals(true, extras["honor.smart_capsule"])
        assertEquals(true, extras["com.hihonor.systemui.smart_capsule"])
        assertEquals(true, extras["com.honor.systemui.capsule"])
        assertEquals(1, extras["honor.capsule_type"])

        // 5. Transsion Dynamic Bar & Dynamic Port (Infinix, Tecno, Itel)
        assertEquals(true, extras["transsion.dynamic_bar"])
        assertEquals(true, extras["com.transsion.systemui.dynamic_bar"])
        assertEquals(true, extras["dynamic_bar_enable"])

        // 6. Vivo Origin Island & Atomic Island (原子岛)
        assertEquals(true, extras["vivo.summary"])
        assertEquals(true, extras["vivo.island"])
        assertEquals(true, extras["com.vivo.notification.island"])

        // 7. Samsung One UI Now Bar
        assertEquals(true, extras["com.samsung.android.nowbar"])
    }

    @Test
    fun testBuildOemCapsuleExtrasMap_whenPaused_clearsOngoingStatusAndCapsuleTriggers() {
        val extras = SimpMediaNotificationProvider.buildOemCapsuleExtrasMap(
            isPlaying = false,
            artist = "Artist Name",
            title = "Track Title",
        )

        // Ongoing should be false when paused so user can dismiss notification
        assertEquals(false, extras["android.ongoing"])
        assertEquals(false, extras["android.extra.ongoing"])
        assertEquals(false, extras["android.requestPromotedOngoing"])

        // OEM active capsule triggers should be false when paused
        assertEquals(false, extras["miui.showFocusNotification"])
        assertEquals(false, extras["showFocusNotification"])
        assertEquals(false, extras["oplus.live_alert"])
        assertEquals(false, extras["coloros.live_alert"])
        assertEquals(false, extras["oplus.capsule"])
        assertEquals(false, extras["honor.smart_capsule"])
        assertEquals(false, extras["transsion.dynamic_bar"])
        assertEquals(false, extras["vivo.island"])
    }
}
