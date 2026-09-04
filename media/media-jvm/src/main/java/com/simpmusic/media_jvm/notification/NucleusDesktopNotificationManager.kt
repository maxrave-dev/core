package com.simpmusic.media_jvm.notification

import com.maxrave.domain.manager.DataStoreManager
import com.maxrave.domain.notification.DesktopNotificationManager
import com.maxrave.domain.notification.DesktopNotificationPermissionState
import com.maxrave.logger.Logger
import dev.nucleusframework.notification.AuthorizationOption
import dev.nucleusframework.notification.AuthorizationStatus
import dev.nucleusframework.notification.NotificationCenter
import dev.nucleusframework.notification.common.NotificationHandle
import dev.nucleusframework.notification.common.NotificationManager
import dev.nucleusframework.notification.common.NotificationResult
import dev.nucleusframework.notification.common.notification
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "DesktopNotificationManager"
private const val DO_NOT_ASK_AGAIN_KEY = "desktop_notification_permission_do_not_ask"
private const val MACOS_NOTIFICATION_SETTINGS_URL =
    "x-apple.systempreferences:com.apple.Notifications-Settings.extension"

internal class NucleusDesktopNotificationManager(
    private val dataStoreManager: DataStoreManager,
) : DesktopNotificationManager {
    private val initialized = AtomicBoolean(false)
    private val platform = DesktopNotificationPlatform.current()
    private val dispatcher =
        Executors
            .newSingleThreadExecutor { runnable ->
                Thread(runnable, "Desktop-Notification-Thread").apply { isDaemon = true }
            }.asCoroutineDispatcher()
    private val scope = CoroutineScope(dispatcher + SupervisorJob())

    private val _permissionState =
        MutableStateFlow(DesktopNotificationPermissionState.CHECKING)
    override val permissionState: StateFlow<DesktopNotificationPermissionState> =
        _permissionState

    private val _shouldShowPermissionDialog = MutableStateFlow(false)
    override val shouldShowPermissionDialog: StateFlow<Boolean> =
        _shouldShowPermissionDialog

    /** Native handles and pending work are confined to [dispatcher]. */
    private val handles = mutableMapOf<String, NotificationHandle>()
    private val pendingNotifications = linkedMapOf<String, PendingNotification>()
    private var requestedPermissionThisSession = false
    private var showedPermissionDialogThisSession = false

    override fun initialize() {
        if (!initialized.compareAndSet(false, true)) return
        scope.launch {
            NotificationManager.initialize()
            if (!NotificationManager.isAvailable()) {
                _permissionState.value = DesktopNotificationPermissionState.UNAVAILABLE
                pendingNotifications.clear()
                Logger.w(TAG, "Desktop notifications unavailable on this platform/session")
                return@launch
            }

            when (platform) {
                DesktopNotificationPlatform.MACOS -> queryMacPermission(showReminder = true)
                DesktopNotificationPlatform.WINDOWS,
                DesktopNotificationPlatform.LINUX,
                -> {
                    _permissionState.value = DesktopNotificationPermissionState.NOT_REQUIRED
                    flushPendingNotifications()
                }
                DesktopNotificationPlatform.OTHER -> {
                    _permissionState.value = DesktopNotificationPermissionState.UNAVAILABLE
                    pendingNotifications.clear()
                }
            }
        }
    }

    override fun refreshPermission() {
        if (platform != DesktopNotificationPlatform.MACOS) return
        initialize()
        scope.launch {
            queryMacPermission(showReminder = false)
        }
    }

    override fun post(
        id: String,
        title: String,
        message: String,
    ) {
        require(id.isNotBlank()) { "Notification id must not be blank" }
        initialize()
        scope.launch {
            val pending = PendingNotification(title = title, message = message)
            when (_permissionState.value) {
                DesktopNotificationPermissionState.AUTHORIZED,
                DesktopNotificationPermissionState.NOT_REQUIRED,
                -> postNow(id, pending)
                DesktopNotificationPermissionState.CHECKING,
                DesktopNotificationPermissionState.REQUESTING,
                -> pendingNotifications[id] = pending
                DesktopNotificationPermissionState.DENIED,
                DesktopNotificationPermissionState.UNAVAILABLE,
                -> Unit
            }
        }
    }

    override fun dismiss(id: String) {
        scope.launch {
            pendingNotifications.remove(id)
            handles.remove(id)?.let { handle ->
                runCatching { handle.dismiss() }
                    .onFailure { Logger.w(TAG, "Could not dismiss notification '$id': ${it.message}") }
            }
        }
    }

    override fun dismissPermissionDialog(doNotShowAgain: Boolean) {
        scope.launch {
            persistDoNotAskAgain(doNotShowAgain)
            _shouldShowPermissionDialog.value = false
        }
    }

    override fun openNotificationSettings(doNotShowAgain: Boolean) {
        if (platform != DesktopNotificationPlatform.MACOS) return
        scope.launch {
            persistDoNotAskAgain(doNotShowAgain)
            _shouldShowPermissionDialog.value = false
            runCatching {
                ProcessBuilder("open", MACOS_NOTIFICATION_SETTINGS_URL).start()
            }.onFailure {
                Logger.w(TAG, "Could not open macOS notification settings: ${it.message}")
            }
        }
    }

    private fun queryMacPermission(showReminder: Boolean) {
        NotificationCenter.getNotificationSettings { settings ->
            scope.launch {
                when (settings.authorizationStatus) {
                    AuthorizationStatus.NOT_DETERMINED -> requestMacPermission()
                    AuthorizationStatus.DENIED -> handleMacPermissionDenied(showReminder)
                    AuthorizationStatus.AUTHORIZED,
                    AuthorizationStatus.PROVISIONAL,
                    AuthorizationStatus.EPHEMERAL,
                    -> {
                        _permissionState.value = DesktopNotificationPermissionState.AUTHORIZED
                        _shouldShowPermissionDialog.value = false
                        flushPendingNotifications()
                    }
                }
            }
        }
    }

    private fun requestMacPermission() {
        if (requestedPermissionThisSession) return
        requestedPermissionThisSession = true
        _permissionState.value = DesktopNotificationPermissionState.REQUESTING
        NotificationCenter.requestAuthorization(
            setOf(AuthorizationOption.ALERT, AuthorizationOption.SOUND),
        ) { _, error ->
            scope.launch {
                if (error != null) {
                    Logger.w(TAG, "Could not request notification permission: $error")
                }
                // The OS result is authoritative. Do not show the reminder in this process when
                // the first system prompt is denied; it is considered on the next app launch.
                queryMacPermission(showReminder = false)
            }
        }
    }

    private suspend fun handleMacPermissionDenied(showReminder: Boolean) {
        _permissionState.value = DesktopNotificationPermissionState.DENIED
        pendingNotifications.clear()
        if (!showReminder || requestedPermissionThisSession || showedPermissionDialogThisSession) return
        if (dataStoreManager.getString(DO_NOT_ASK_AGAIN_KEY).first() == DataStoreManager.TRUE) return

        showedPermissionDialogThisSession = true
        _shouldShowPermissionDialog.value = true
    }

    private suspend fun persistDoNotAskAgain(doNotShowAgain: Boolean) {
        if (doNotShowAgain) {
            dataStoreManager.putString(DO_NOT_ASK_AGAIN_KEY, DataStoreManager.TRUE)
        }
    }

    private fun flushPendingNotifications() {
        if (pendingNotifications.isEmpty()) return
        val pending = pendingNotifications.toMap()
        pendingNotifications.clear()
        pending.forEach { (id, request) -> postNow(id, request) }
    }

    private fun postNow(
        id: String,
        request: PendingNotification,
    ) {
        handles.remove(id)?.let { oldHandle -> runCatching { oldHandle.dismiss() } }
        when (
            val result =
                notification(
                    title = request.title,
                    message = request.message,
                ).send()
        ) {
            is NotificationResult.Success -> {
                handles[id] = result.handle
                Logger.d(TAG, "Posted notification '$id': ${request.title}")
            }
            is NotificationResult.Failure -> {
                Logger.w(TAG, "Notification '$id' rejected: ${result.reason}")
            }
        }
    }

    private data class PendingNotification(
        val title: String,
        val message: String,
    )
}

private enum class DesktopNotificationPlatform {
    MACOS,
    WINDOWS,
    LINUX,
    OTHER,
    ;

    companion object {
        fun current(): DesktopNotificationPlatform {
            val osName = System.getProperty("os.name", "").lowercase(Locale.ENGLISH)
            return when {
                osName.contains("mac") || osName.contains("darwin") -> MACOS
                osName.contains("win") -> WINDOWS
                osName.contains("nux") || osName.contains("nix") || osName.contains("aix") -> LINUX
                else -> OTHER
            }
        }
    }
}
