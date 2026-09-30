package com.maxrave.domain.data.player

/**
 * One place the sound can leave by: the device's own speaker, a wired or Bluetooth headset, a USB
 * DAC, HDMI.
 *
 * [id] is opaque — only the backend that listed the output can route to it (an `AudioDeviceInfo`
 * id on Android, an mpv `audio-device` name on desktop). [isActive] marks the one the sound is
 * leaving by right now, whether the user picked it or the system did.
 */
data class AudioOutput(
    val id: String,
    val name: String,
    val kind: AudioOutputKind,
    val isActive: Boolean,
)

enum class AudioOutputKind {
    DEVICE_SPEAKER,
    WIRED,
    BLUETOOTH,
    USB,
    HDMI,
    OTHER,
}
