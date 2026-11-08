package com.maxrave.media3.exoplayer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.maxrave.domain.data.player.AudioOutput
import com.maxrave.domain.data.player.AudioOutputKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The outputs music can leave this phone by, and the one the user picked for this app.
 *
 * Routing goes through `ExoPlayer.setPreferredAudioDevice` — the one routing decision the app owns
 * and the one that actually takes effect for its own player. The system output switcher routes the
 * whole phone instead, and `MediaRouter.selectRoute` is a request the framework is free to ignore.
 *
 * [onPreferredChanged] hands the chosen device (null = back to the system's choice) to the owner,
 * which applies it to every player it runs. Callbacks arrive on the main thread.
 */
internal class AudioOutputRouter(
    private val context: Context,
    private val onPreferredChanged: (AudioDeviceInfo?) -> Unit,
) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val _outputs = MutableStateFlow<List<AudioOutput>>(emptyList())
    val outputs: StateFlow<List<AudioOutput>> = _outputs.asStateFlow()

    /** The device the user picked, or null while the system decides. Read when a player is built. */
    @Volatile
    var preferredDevice: AudioDeviceInfo? = null
        private set

    private val deviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = refresh()

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = refresh()
        }

    init {
        // The callback fires once straight away with every current device, which fills the list.
        audioManager?.registerAudioDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
    }

    /** Route to the output with [id], or back to the system when null or no longer present. */
    fun select(id: String?) {
        val devices = usableDevices()
        val device = id?.toIntOrNull()?.let { wanted -> devices.firstOrNull { it.id == wanted } }
        // Picking what the system is already using is not a preference. Pinning it would hold playback
        // on the phone speaker after a headset is plugged in later, which nobody asked for.
        preferredDevice = device?.takeIf { it.id != systemDeviceId(devices) }
        onPreferredChanged(preferredDevice)
        refresh()
    }

    fun release() {
        audioManager?.unregisterAudioDeviceCallback(deviceCallback)
    }

    fun refresh() {
        val devices = usableDevices()
        // A picked device that went away (headset unplugged, out of range) is forgotten, so the
        // system's own fallback applies and a later reconnect is not silently re-captured.
        val picked = preferredDevice
        if (picked != null && devices.none { it.id == picked.id }) {
            preferredDevice = null
            onPreferredChanged(null)
        }
        val activeId = activeDeviceId(devices)
        _outputs.value =
            devices
                .map { device ->
                    AudioOutput(
                        id = device.id.toString(),
                        name = nameOf(device),
                        kind = kindOf(device.type) ?: AudioOutputKind.OTHER,
                        isActive = device.id == activeId,
                    )
                }.sortedBy { if (it.kind == AudioOutputKind.DEVICE_SPEAKER) 0 else 1 }
    }

    // Outputs music can be sent to, one entry per physical device: a Bluetooth headset is listed
    // once for media (A2DP) and once for calls (SCO), and LE Audio as both a headset and a speaker.
    private fun usableDevices(): List<AudioDeviceInfo> {
        val all = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
        return all
            .filter { kindOf(it.type) != null }
            .distinctBy { device ->
                val address = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) device.address else ""
                "${kindOf(device.type)}|${address.ifEmpty { device.productName?.toString().orEmpty() }}"
            }
    }

    private fun activeDeviceId(devices: List<AudioDeviceInfo>): Int? {
        preferredDevice?.let { picked -> if (devices.any { it.id == picked.id }) return picked.id }
        return systemDeviceId(devices)
    }

    // Where the system itself sends media, ignoring this app's pick — per-player routing does not
    // show up in the system's own answer.
    private fun systemDeviceId(devices: List<AudioDeviceInfo>): Int? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val routed = audioManager?.getAudioDevicesForAttributes(MEDIA_ATTRIBUTES).orEmpty()
            routed.firstNotNullOfOrNull { route -> devices.firstOrNull { it.id == route.id } }?.let { return it.id }
        }
        // Below Android 13 nothing says where media is routed, so answer the way the system itself
        // chooses: a headset over the speaker, Bluetooth over a wire.
        return devices.maxByOrNull { device ->
            when (kindOf(device.type)) {
                AudioOutputKind.BLUETOOTH -> 5
                AudioOutputKind.WIRED -> 4
                AudioOutputKind.USB -> 3
                AudioOutputKind.HDMI -> 2
                AudioOutputKind.OTHER -> 1
                else -> 0
            }
        }?.id
    }

    // The speaker takes the name the owner gave the phone in Settings — "maxrave's Phone" — rather
    // than a model number. Anything else keeps its product name ("Pixel Buds Pro 2"), except that a
    // wire, HDMI and many USB devices report the PHONE's model as theirs: those come back blank, for
    // the UI to label by kind in the user's language.
    private fun nameOf(device: AudioDeviceInfo): String =
        if (device.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
                ?: Build.MODEL
        } else {
            device.productName?.toString()?.takeIf { it.isNotBlank() && it != Build.MODEL }.orEmpty()
        }

    private companion object {
        val MEDIA_ATTRIBUTES: AudioAttributes =
            AudioAttributes
                .Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()

        // Null for everything music is never sent to: the earpiece, call-only Bluetooth, the
        // telephony and submix buses, the "safe" speaker used for alarms.
        fun kindOf(type: Int): AudioOutputKind? =
            when (type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> AudioOutputKind.DEVICE_SPEAKER
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_LINE_ANALOG,
                AudioDeviceInfo.TYPE_LINE_DIGITAL,
                AudioDeviceInfo.TYPE_AUX_LINE,
                -> AudioOutputKind.WIRED
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER,
                AudioDeviceInfo.TYPE_HEARING_AID,
                -> AudioOutputKind.BLUETOOTH
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_ACCESSORY,
                -> AudioOutputKind.USB
                AudioDeviceInfo.TYPE_HDMI,
                AudioDeviceInfo.TYPE_HDMI_ARC,
                AudioDeviceInfo.TYPE_HDMI_EARC,
                -> AudioOutputKind.HDMI
                AudioDeviceInfo.TYPE_DOCK -> AudioOutputKind.OTHER
                else -> null
            }
    }
}
