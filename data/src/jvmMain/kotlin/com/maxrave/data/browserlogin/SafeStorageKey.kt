package com.maxrave.data.browserlogin

import com.maxrave.domain.repository.BrowserLoginException
import com.maxrave.domain.repository.BrowserLoginFailure
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference
import java.util.concurrent.TimeUnit

/** Called only for the browser explicitly selected by the user, never during discovery. */
internal object SafeStorageKey {
    fun password(browser: String, macOS: Boolean): ByteArray = try {
        if (macOS) macPassword(browser) else linuxPassword(browser)
    } catch (error: BrowserLoginException) {
        throw error
    } catch (_: LinkageError) {
        throw BrowserLoginException(BrowserLoginFailure.KEY_UNAVAILABLE)
    } catch (_: Exception) {
        throw BrowserLoginException(BrowserLoginFailure.KEY_UNAVAILABLE)
    }

    private fun macPassword(browser: String): ByteArray {
        val foundation = Native.load(CORE_FOUNDATION, CoreFoundationNative::class.java)
        val security = Native.load(SECURITY, SecurityNative::class.java)
        val constants = NativeLibrary.getInstance(SECURITY)
        val query = foundation.CFDictionaryCreateMutable(null, NativeLong(0), null, null)
            ?: throw BrowserLoginException(BrowserLoginFailure.KEY_UNAVAILABLE)
        val strings = mutableListOf<Pointer>()
        var data: Pointer? = null
        fun constant(name: String): Pointer = constants.getGlobalVariableAddress(name).getPointer(0)
        fun string(value: String): Pointer = foundation.CFStringCreateWithCString(null, value, 0x08000100)
            ?.also { strings += it } ?: throw BrowserLoginException(BrowserLoginFailure.KEY_UNAVAILABLE)
        try {
            foundation.CFDictionarySetValue(query, constant("kSecClass"), constant("kSecClassGenericPassword"))
            foundation.CFDictionarySetValue(query, constant("kSecAttrService"), string("$browser Safe Storage"))
            foundation.CFDictionarySetValue(query, constant("kSecAttrAccount"), string(browser))
            foundation.CFDictionarySetValue(query, constant("kSecMatchLimit"), constant("kSecMatchLimitOne"))
            val trueValue = NativeLibrary.getInstance(CORE_FOUNDATION).getGlobalVariableAddress("kCFBooleanTrue").getPointer(0)
            foundation.CFDictionarySetValue(query, constant("kSecReturnData"), trueValue)
            val result = PointerByReference()
            // Direct Security.framework access attributes authorization to this process, not to the
            // security CLI. The displayed identity still needs verification in the packaged app.
            val status = security.SecItemCopyMatching(query, result)
            data = result.value
            if (status != 0) {
                throw BrowserLoginException(
                    if (status == -128 || status == -25293 || status == -25308) BrowserLoginFailure.ACCESS_DENIED
                    else BrowserLoginFailure.KEY_UNAVAILABLE,
                )
            }
            val value = data ?: throw BrowserLoginException(BrowserLoginFailure.KEY_UNAVAILABLE)
            val length = foundation.CFDataGetLength(value).toLong()
            if (length !in 1..MAX_PASSWORD_BYTES.toLong()) throw BrowserLoginException(BrowserLoginFailure.KEY_UNAVAILABLE)
            return foundation.CFDataGetBytePtr(value)?.getByteArray(0, length.toInt())
                ?: throw BrowserLoginException(BrowserLoginFailure.KEY_UNAVAILABLE)
        } finally {
            data?.let(foundation::CFRelease)
            foundation.CFRelease(query)
            strings.forEach(foundation::CFRelease)
        }
    }

    private fun linuxPassword(browser: String): ByteArray {
        val desktop = System.getenv("XDG_CURRENT_DESKTOP").orEmpty()
        val kde = desktop.split(':').any { it.equals("KDE", ignoreCase = true) }
        val backends = if (kde) listOf(::kwalletPassword, ::secretServicePassword) else listOf(::secretServicePassword, ::kwalletPassword)
        backends.forEach { backend ->
            try {
                return backend(browser)
            } catch (error: BrowserLoginException) {
                // Once a native authorization request was denied, do not try another backend.
                if (error.reason == BrowserLoginFailure.ACCESS_DENIED) throw error
            } catch (_: LinkageError) {
                // An optional native backend is absent. Nothing is installed automatically.
            }
        }
        throw BrowserLoginException(BrowserLoginFailure.KEY_UNAVAILABLE)
    }

    private fun secretServicePassword(browser: String): ByteArray {
        val secret = Native.load("secret-1", SecretNative::class.java)
        val glib = Native.load("glib-2.0", GlibNative::class.java)
        val objects = Native.load("gobject-2.0", GObjectNative::class.java)
        val nativeGlib = NativeLibrary.getInstance("glib-2.0")
        val error = PointerByReference()
        var service: Pointer? = null
        var attributes: Pointer? = null
        var items: Pointer? = null
        fun discardError() {
            error.value?.let(glib::g_error_free)
            error.value = null
        }
        try {
            // An uncached service instance bounds libsecret's item/secret cache to this import.
            service = secret.secret_service_open_sync(secret.secret_service_get_type(), null, 2, null, error)
            if (service == null) throw BrowserLoginException(BrowserLoginFailure.KEY_UNAVAILABLE)
            discardError()
            attributes = glib.g_hash_table_new(nativeGlib.getFunction("g_str_hash"), nativeGlib.getFunction("g_str_equal"))
                ?: throw BrowserLoginException(BrowserLoginFailure.KEY_UNAVAILABLE)
            // Search labels only. Do not use LOAD_SECRETS or UNLOCK on the full result set.
            items = secret.secret_service_search_sync(service, null, attributes, 2, null, error)
            if (error.value != null) throw BrowserLoginException(BrowserLoginFailure.KEY_UNAVAILABLE)
            val matches = listPointers(items).filter { item ->
                val label = secret.secret_item_get_label(item) ?: return@filter false
                try {
                    label.getString(0, "UTF-8") == "$browser Safe Storage"
                } finally {
                    glib.g_free(label)
                }
            }
            val item = matches.firstOrNull { secret.secret_item_get_locked(it) == 0 } ?: matches.firstOrNull()
                ?: throw BrowserLoginException(BrowserLoginFailure.KEY_UNAVAILABLE)
            if (secret.secret_item_get_locked(item) != 0) {
                val selected = glib.g_list_append(null, item)
                try {
                    if (secret.secret_service_unlock_sync(service, selected, null, null, error) <= 0 || error.value != null) {
                        throw BrowserLoginException(BrowserLoginFailure.ACCESS_DENIED)
                    }
                } finally {
                    selected?.let(glib::g_list_free)
                }
            }
            if (secret.secret_item_load_secret_sync(item, null, error) == 0 || error.value != null) {
                throw BrowserLoginException(BrowserLoginFailure.ACCESS_DENIED)
            }
            val value = secret.secret_item_get_secret(item) ?: throw BrowserLoginException(BrowserLoginFailure.KEY_UNAVAILABLE)
            try {
                val length = NativeLongByReference()
                val bytes = secret.secret_value_get(value, length) ?: throw BrowserLoginException(BrowserLoginFailure.KEY_UNAVAILABLE)
                val count = length.value.toLong()
                if (count !in 1..MAX_PASSWORD_BYTES.toLong()) throw BrowserLoginException(BrowserLoginFailure.KEY_UNAVAILABLE)
                return bytes.getByteArray(0, count.toInt())
            } finally {
                secret.secret_value_unref(value)
            }
        } finally {
            discardError()
            listPointers(items).forEach(objects::g_object_unref)
            items?.let(glib::g_list_free)
            attributes?.let(glib::g_hash_table_unref)
            service?.let(objects::g_object_unref)
        }
    }

    private fun kwalletPassword(browser: String): ByteArray {
        // Match yt-dlp's KWallet 4/5/6 service selection and configured network wallet.
        val version = System.getenv("KDE_SESSION_VERSION")
        val suffix = when (version) { "6" -> "6"; "5" -> "5"; else -> "" }
        val wallet = runCommand(
            listOf("dbus-send", "--session", "--print-reply=literal", "--dest=org.kde.kwalletd$suffix", "/modules/kwalletd$suffix", "org.kde.KWallet.networkWallet"),
            authorization = false,
        )?.let { bytes ->
            try {
                String(bytes, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() && it.length <= 256 && it.none { char -> char.isISOControl() } }
            } finally {
                bytes.fill(0)
            }
        } ?: "kdewallet"
        val result = runCommand(
            listOf("kwallet-query", "--read-password", "$browser Safe Storage", "--folder", "$browser Keys", wallet),
            authorization = true,
        ) ?: throw BrowserLoginException(BrowserLoginFailure.KEY_UNAVAILABLE)
        var end = result.size
        while (end > 0 && (result[end - 1] == 10.toByte() || result[end - 1] == 13.toByte())) end--
        try {
            val failure = "failed to read".toByteArray(Charsets.US_ASCII)
            val failed = end >= failure.size && failure.indices.all { index ->
                val byte = result[index].toInt() and 0xff
                (if (byte in 65..90) byte + 32 else byte) == failure[index].toInt()
            }
            if (end == 0 || failed) {
                throw BrowserLoginException(BrowserLoginFailure.KEY_UNAVAILABLE)
            }
            return result.copyOf(end)
        } finally {
            result.fill(0)
        }
    }

    private fun runCommand(arguments: List<String>, authorization: Boolean): ByteArray? {
        val process = try {
            ProcessBuilder(arguments).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        } catch (_: java.io.IOException) {
            return null
        }
        try {
            if (!process.waitFor(if (authorization) 90 else 5, TimeUnit.SECONDS)) {
                throw BrowserLoginException(if (authorization) BrowserLoginFailure.ACCESS_DENIED else BrowserLoginFailure.KEY_UNAVAILABLE)
            }
            if (process.exitValue() != 0) {
                if (authorization) throw BrowserLoginException(BrowserLoginFailure.ACCESS_DENIED)
                return null
            }
            return process.inputStream.use {
                val bytes = it.readNBytes(MAX_PASSWORD_BYTES + 1)
                if (bytes.size > MAX_PASSWORD_BYTES) {
                    bytes.fill(0)
                    throw BrowserLoginException(BrowserLoginFailure.KEY_UNAVAILABLE)
                }
                bytes
            }
        } finally {
            process.destroyForcibly()
            process.inputStream.close()
            process.outputStream.close()
            process.errorStream.close()
        }
    }

    private fun listPointers(head: Pointer?): List<Pointer> {
        val values = mutableListOf<Pointer>()
        var node = head
        while (node != null && values.size < 10_000) {
            node.getPointer(0)?.let { values += it }
            node = node.getPointer(Native.POINTER_SIZE.toLong())
        }
        return values
    }

    private interface SecurityNative : Library {
        fun SecItemCopyMatching(query: Pointer, result: PointerByReference): Int
    }

    private interface CoreFoundationNative : Library {
        fun CFDictionaryCreateMutable(allocator: Pointer?, capacity: NativeLong, keyCallbacks: Pointer?, valueCallbacks: Pointer?): Pointer?
        fun CFDictionarySetValue(dictionary: Pointer, key: Pointer, value: Pointer)
        fun CFStringCreateWithCString(allocator: Pointer?, value: String, encoding: Int): Pointer?
        fun CFDataGetLength(data: Pointer): NativeLong
        fun CFDataGetBytePtr(data: Pointer): Pointer?
        fun CFRelease(value: Pointer)
    }

    private interface SecretNative : Library {
        fun secret_service_get_type(): NativeLong
        fun secret_service_open_sync(type: NativeLong, busName: String?, flags: Int, cancellable: Pointer?, error: PointerByReference): Pointer?
        fun secret_service_search_sync(service: Pointer, schema: Pointer?, attributes: Pointer, flags: Int, cancellable: Pointer?, error: PointerByReference): Pointer?
        fun secret_item_get_label(item: Pointer): Pointer?
        fun secret_item_get_locked(item: Pointer): Int
        fun secret_service_unlock_sync(service: Pointer, items: Pointer?, cancellable: Pointer?, unlocked: Pointer?, error: PointerByReference): Int
        fun secret_item_load_secret_sync(item: Pointer, cancellable: Pointer?, error: PointerByReference): Int
        fun secret_item_get_secret(item: Pointer): Pointer?
        fun secret_value_get(value: Pointer, length: NativeLongByReference): Pointer?
        fun secret_value_unref(value: Pointer)
    }

    private interface GlibNative : Library {
        fun g_hash_table_new(hash: Pointer, equal: Pointer): Pointer?
        fun g_hash_table_unref(table: Pointer)
        fun g_list_append(list: Pointer?, value: Pointer): Pointer?
        fun g_list_free(list: Pointer)
        fun g_error_free(error: Pointer)
        fun g_free(value: Pointer)
    }

    private interface GObjectNative : Library {
        fun g_object_unref(value: Pointer)
    }

    private const val MAX_PASSWORD_BYTES = 8_192
    private const val CORE_FOUNDATION = "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation"
    private const val SECURITY = "/System/Library/Frameworks/Security.framework/Security"
}
