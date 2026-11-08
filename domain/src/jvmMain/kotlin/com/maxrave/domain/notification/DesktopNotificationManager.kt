package com.maxrave.domain.notification

import kotlinx.coroutines.flow.StateFlow

/**
 * Desktop-only gateway for native operating-system notifications.
 *
 * Implementations own their native threading, permission lifecycle, and notification handles.
 * Callers only submit logical notification IDs, so no Nucleus or platform type leaks into the UI
 * or media layers.
 */
interface DesktopNotificationManager {
    val permissionState: StateFlow<DesktopNotificationPermissionState>

    /** True only for the macOS reminder shown on a later launch after permission was denied. */
    val shouldShowPermissionDialog: StateFlow<Boolean>

    /** Starts platform initialization and, on first-use macOS installs, requests authorization. */
    fun initialize()

    /** Re-reads macOS authorization after the app regains focus from System Settings. */
    fun refreshPermission()

    /**
     * Posts or replaces a notification under [id]. This method never blocks its caller.
     */
    fun post(
        id: String,
        title: String,
        message: String,
    )

    /** Dismisses a delivered or not-yet-delivered notification under [id]. */
    fun dismiss(id: String)

    /** Closes the macOS reminder, optionally suppressing it on future launches. */
    fun dismissPermissionDialog(doNotShowAgain: Boolean)

    /** Closes the reminder and opens macOS notification settings. */
    fun openNotificationSettings(doNotShowAgain: Boolean)
}

enum class DesktopNotificationPermissionState {
    CHECKING,
    REQUESTING,
    AUTHORIZED,
    DENIED,
    NOT_REQUIRED,
    UNAVAILABLE,
}
