package org.simpmusic.loginsync

import java.io.File
import java.net.InetAddress

/** The name the user knows this computer by. Blocking on macOS (runs scutil). */
fun desktopDeviceName(): DeviceName {
    val osName = System.getProperty("os.name").orEmpty()
    val isMac = osName.startsWith("Mac")
    val name =
        when {
            // The friendly name from System Settings ("Minh's MacBook Pro"), not the hostname.
            isMac -> {
                runCatching {
                    val process = ProcessBuilder("scutil", "--get", "ComputerName").start()
                    val out = process.inputStream.bufferedReader().use { it.readText() }.trim()
                    out.takeIf { process.waitFor() == 0 }
                }.getOrNull()
            }

            osName.startsWith("Windows") -> {
                System.getenv("COMPUTERNAME")
            }

            else -> {
                System.getenv("HOSTNAME") ?: runCatching { File("/etc/hostname").readText().trim() }.getOrNull()
            }
        }?.takeIf { it.isNotBlank() }
            ?: runCatching { InetAddress.getLocalHost().hostName }.getOrNull()
            ?: "SimpMusic Desktop"
    return DeviceName(name = name, os = if (isMac) "macOS" else osName)
}
