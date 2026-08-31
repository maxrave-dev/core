package com.maxrave.data.io

/**
 * Reads the bytes of an image the user picked, given whatever string the platform's picker handed
 * back — a `content://` uri on Android, a file path or `file:` uri on Desktop.
 *
 * Returns null instead of throwing: every caller is reacting to something the user did, and the
 * picked file can be gone, unreadable, or on a revoked permission by the time it is read.
 */
expect suspend fun readLocalImageBytes(uri: String): ByteArray?
