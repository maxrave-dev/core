package com.maxrave.data.browserlogin

import com.maxrave.domain.repository.BrowserCookies
import com.maxrave.domain.repository.BrowserLoginException
import com.maxrave.domain.repository.BrowserLoginFailure
import com.maxrave.domain.repository.BrowserLoginRepository
import com.maxrave.domain.repository.BrowserLoginTarget
import com.maxrave.domain.repository.BrowserProfile
import com.maxrave.domain.repository.BrowserProfileStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

class BrowserLoginRepositoryImpl internal constructor(
    private val home: Path = Paths.get(System.getProperty("user.home")),
    osName: String = System.getProperty("os.name"),
    private val applicationDirectories: List<Path> = listOf(Paths.get("/Applications"), Paths.get("/System/Applications"), home.resolve("Applications")),
    private val snapshots: BrowserCookieSnapshot = BrowserCookieSnapshot(),
) : BrowserLoginRepository {
    private val mutex = Mutex()
    private val sources = mutableMapOf<String, Source>()
    private val os = osName.lowercase()
    private val macOS = os.contains("mac") || os.contains("darwin")
    private val windows = os.contains("win") && !macOS

    override suspend fun profiles(target: BrowserLoginTarget): List<BrowserProfile> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val found = mutableListOf<Source>()
            if (!windows) chromiumRoots().forEach { (browser, root, keyring) -> discoverChromium(browser, root, keyring, found) }
            geckoRoots().forEach { (browser, root) -> discoverGecko(browser, root, found) }
            if (macOS) discoverSafari(found)
            val unique = found.distinctBy { it.browser to it.path.toAbsolutePath().normalize() }
            val profiles = unique.map { source ->
                coroutineContext.ensureActive()
                BrowserProfile(id(source), source.browser, source.name, inspect(source, target))
            }
            sources.clear()
            unique.forEach { sources[id(it)] = it }
            profiles.sortedWith(compareBy<BrowserProfile> { it.browser }.thenBy { it.name })
        }
    }

    override suspend fun cookies(profile: BrowserProfile, target: BrowserLoginTarget): BrowserCookies = withContext(Dispatchers.IO) {
        mutex.withLock {
            val source = sources[profile.id] ?: throw BrowserLoginException(BrowserLoginFailure.NO_COOKIES)
            try {
                val records = when (source.kind) {
                    Kind.CHROMIUM -> ChromiumCookieReader.read(source.path, snapshots, source.keyring, macOS, target)
                    Kind.GECKO -> GeckoCookieReader.read(source.path, snapshots, includeValues = true, target = target)
                    Kind.SAFARI -> SafariCookieReader.read(source.path, snapshots, includeValues = true, target = target)
                }
                // A cancelled screen must not publish credentials after a native authorization UI
                // returns. OS key-store APIs themselves are synchronous and run off the UI thread.
                coroutineContext.ensureActive()
                BrowserCookieRecord.toCookies(records, target)
            } catch (error: CancellationException) {
                throw error
            } catch (error: BrowserLoginException) {
                throw error
            } catch (_: AccessDeniedException) {
                throw BrowserLoginException(BrowserLoginFailure.FILE_ACCESS_DENIED)
            } catch (_: SecurityException) {
                throw BrowserLoginException(BrowserLoginFailure.FILE_ACCESS_DENIED)
            } catch (_: java.nio.file.NoSuchFileException) {
                throw BrowserLoginException(BrowserLoginFailure.NO_COOKIES)
            } catch (error: java.nio.file.FileSystemException) {
                throw BrowserLoginException(
                    if (error.reason.orEmpty().contains("permitted", ignoreCase = true) || error.reason.orEmpty().contains("denied", ignoreCase = true)) {
                        BrowserLoginFailure.FILE_ACCESS_DENIED
                    } else {
                        BrowserLoginFailure.INVALID_DATA
                    },
                )
            } catch (_: LinkageError) {
                throw BrowserLoginException(BrowserLoginFailure.KEY_UNAVAILABLE)
            } catch (_: Exception) {
                throw BrowserLoginException(BrowserLoginFailure.INVALID_DATA)
            }
        }
    }

    private fun inspect(source: Source, target: BrowserLoginTarget): BrowserProfileStatus = try {
        if (source.kind == Kind.CHROMIUM) {
            ChromiumCookieReader.inspect(source.path, snapshots, macOS, target)
        } else {
            val records = when (source.kind) {
                Kind.GECKO -> GeckoCookieReader.read(source.path, snapshots, includeValues = false, target = target)
                Kind.SAFARI -> SafariCookieReader.read(source.path, snapshots, includeValues = false, target = target)
                Kind.CHROMIUM -> error("Handled above")
            }
            val now = System.currentTimeMillis() / 1_000
            if (records.any { it.matchesRequestHost(target) && it.path == "/" && it.isCurrent(now) && BrowserCookieRecord.isAuthenticationName(it.name, target) }) {
                BrowserProfileStatus.COOKIES_FOUND
            } else {
                BrowserProfileStatus.NO_COOKIES
            }
        }
    } catch (error: BrowserLoginException) {
        when (error.reason) {
            BrowserLoginFailure.FILE_ACCESS_DENIED, BrowserLoginFailure.ACCESS_DENIED -> BrowserProfileStatus.PERMISSION_REQUIRED
            BrowserLoginFailure.UNSUPPORTED_ENCRYPTION -> BrowserProfileStatus.UNSUPPORTED
            BrowserLoginFailure.NO_COOKIES -> BrowserProfileStatus.NO_COOKIES
            else -> BrowserProfileStatus.UNREADABLE
        }
    } catch (_: AccessDeniedException) {
        BrowserProfileStatus.PERMISSION_REQUIRED
    } catch (_: SecurityException) {
        BrowserProfileStatus.PERMISSION_REQUIRED
    } catch (_: java.nio.file.NoSuchFileException) {
        BrowserProfileStatus.NO_COOKIES
    } catch (error: java.nio.file.FileSystemException) {
        if (error.reason.orEmpty().contains("permitted", ignoreCase = true) || error.reason.orEmpty().contains("denied", ignoreCase = true)) {
            BrowserProfileStatus.PERMISSION_REQUIRED
        } else {
            BrowserProfileStatus.UNREADABLE
        }
    } catch (_: LinkageError) {
        BrowserProfileStatus.UNREADABLE
    } catch (_: Exception) {
        BrowserProfileStatus.UNREADABLE
    }

    private fun discoverChromium(browser: String, root: Path, keyring: String, found: MutableList<Source>) {
        // A denied or leftover data path is not proof that an application is installed. Confirm
        // the Mac app first; only then may unreadable profiles produce permission-request rows.
        if (macOS) {
            if (!installedMacBrowser(browser)) return
        } else if (!Files.isDirectory(root)) {
            return
        }
        val names = chromiumProfileNames(root)
        val candidates = linkedSetOf<Path>()
        // Opera stores cookies directly in its root. Other Chromium browsers use profile folders.
        if (browser == "Opera" || listOf(root.resolve("Network/Cookies"), root.resolve("Cookies")).any(::cookieFilePresent)) {
            candidates.add(root)
        }
        names.keys.filter { it.isNotBlank() && it != "." && it != ".." && '/' !in it && '\\' !in it }
            .forEach { candidates.add(root.resolve(it)) }
        childDirectories(root).filterTo(candidates) {
            val name = it.fileName.toString()
            name == "Default" || name.matches(Regex("Profile [0-9]+")) || name in names
        }
        // Listing Local State or the profile directory can itself be denied. A known installed
        // browser must remain visible, so its default store can report the permission error.
        if (candidates.isEmpty()) candidates.add(root.resolve("Default"))
        candidates.forEach { directory ->
            val paths = listOf(directory.resolve("Network/Cookies"), directory.resolve("Cookies"))
            val database = paths.firstOrNull(::cookieFilePresent) ?: paths.first()
            val directoryName = directory.fileName.toString()
            val name = names[directoryName] ?: if (directory == root) "Default" else directoryName
            found += Source(browser, name, database, Kind.CHROMIUM, keyring)
        }
    }

    private fun chromiumProfileNames(root: Path): Map<String, String> = try {
        val state = root.resolve("Local State")
        if (!Files.isRegularFile(state) || Files.size(state) > 8 * 1024 * 1024) {
            emptyMap()
        } else {
            val json = Json.parseToJsonElement(Files.readString(state)) as? JsonObject
            val profile = json?.get("profile") as? JsonObject
            val cache = profile?.get("info_cache") as? JsonObject
            cache?.mapNotNull { (directory, metadata) ->
                val name = ((metadata as? JsonObject)?.get("name") as? JsonPrimitive)?.content
                name?.takeIf { it.isNotBlank() }?.let { directory to it }
            }?.toMap().orEmpty()
        }
    } catch (_: Exception) {
        emptyMap()
    }

    private fun discoverGecko(browser: String, root: Path, found: MutableList<Source>) {
        if (macOS && !installedMacBrowser(browser)) return
        if (!Files.isDirectory(root)) return
        val ini = root.resolve("profiles.ini")
        val profiles = try {
            if (Files.isRegularFile(ini) && Files.size(ini) <= 1024 * 1024) parseIni(Files.readAllLines(ini)) else emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        profiles.filter { it["Path"] != null }.forEach { properties ->
            val value = properties.getValue("Path")
            val directory = try {
                if (properties["IsRelative"] == "0") Paths.get(value) else root.resolve(value)
            } catch (_: Exception) {
                return@forEach
            }
            val database = directory.resolve("cookies.sqlite")
            // Include a registered but unreadable profile; permission denial is not "signed out".
            found += Source(browser, properties["Name"] ?: directory.fileName.toString(), database, Kind.GECKO)
        }
        if (profiles.isEmpty()) {
            val directories = childDirectories(root) + childDirectories(root.resolve("Profiles"))
            directories.filter { Files.isRegularFile(it.resolve("cookies.sqlite")) }.forEach {
                found += Source(browser, it.fileName.toString(), it.resolve("cookies.sqlite"), Kind.GECKO)
            }
        }
    }

    private fun discoverSafari(found: MutableList<Source>) {
        if (!installedMacBrowser("Safari")) return
        val primary = listOf(
            home.resolve("Library/Containers/com.apple.Safari/Data/Library/Cookies/Cookies.binarycookies"),
            home.resolve("Library/Cookies/Cookies.binarycookies"),
        )
        primary.firstOrNull(::cookieFilePresent)?.let { found += Source("Safari", "Default", it, Kind.SAFARI) }
        listOf(
            home.resolve("Library/Containers/com.apple.Safari/Data/Library/WebKit/WebsiteDataStore"),
            home.resolve("Library/WebKit/WebsiteDataStore"),
        ).forEach { root ->
            try {
                if (Files.isDirectory(root)) {
                    Files.walk(root, 5).use { entries ->
                        entries.filter { it.fileName.toString() == "Cookies.binarycookies" && Files.isRegularFile(it) }
                            .limit(100).forEach { file ->
                                val relative = root.relativize(file)
                                found += Source("Safari", relative.getName(0).toString(), file, Kind.SAFARI)
                            }
                    }
                }
            } catch (_: Exception) {
                // The default source below still exposes the explicit Full Disk Access action.
            }
        }
        if (found.none { it.kind == Kind.SAFARI } &&
            applicationDirectories.any { Files.isDirectory(it.resolve("Safari.app")) }
        ) {
            found += Source("Safari", "Default", primary.first(), Kind.SAFARI)
        }
    }

    private fun chromiumRoots(): List<ChromiumRoot> {
        if (macOS) {
            val base = home.resolve("Library/Application Support")
            return listOf(
                ChromiumRoot("Chrome", base.resolve("Google/Chrome"), "Chrome"),
                ChromiumRoot("Chrome Beta", base.resolve("Google/Chrome Beta"), "Chrome"),
                ChromiumRoot("Chrome Canary", base.resolve("Google/Chrome Canary"), "Chrome"),
                ChromiumRoot("Chrome Dev", base.resolve("Google/Chrome Dev"), "Chrome"),
                ChromiumRoot("Chromium", base.resolve("Chromium"), "Chromium"),
                ChromiumRoot("Brave", base.resolve("BraveSoftware/Brave-Browser"), "Brave"),
                ChromiumRoot("Microsoft Edge", base.resolve("Microsoft Edge"), "Microsoft Edge"),
                ChromiumRoot("Vivaldi", base.resolve("Vivaldi"), "Vivaldi"),
                ChromiumRoot("Opera", base.resolve("com.operasoftware.Opera"), "Opera"),
                ChromiumRoot("Arc", base.resolve("Arc/User Data"), "Arc"),
            )
        }
        val config = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) } ?: home.resolve(".config")
        return listOf(
            ChromiumRoot("Chrome", config.resolve("google-chrome"), "Chrome"),
            ChromiumRoot("Chrome Beta", config.resolve("google-chrome-beta"), "Chrome"),
            ChromiumRoot("Chrome Dev", config.resolve("google-chrome-unstable"), "Chrome"),
            ChromiumRoot("Chromium", config.resolve("chromium"), "Chromium"),
            ChromiumRoot("Brave", config.resolve("BraveSoftware/Brave-Browser"), "Brave"),
            ChromiumRoot("Microsoft Edge", config.resolve("microsoft-edge"), "Chromium"),
            ChromiumRoot("Vivaldi", config.resolve("vivaldi"), "Chrome"),
            ChromiumRoot("Opera", config.resolve("opera"), "Chromium"),
            ChromiumRoot("Chromium (Flatpak)", home.resolve(".var/app/org.chromium.Chromium/config/chromium"), "Chromium"),
            ChromiumRoot("Chrome (Flatpak)", home.resolve(".var/app/com.google.Chrome/config/google-chrome"), "Chrome"),
            ChromiumRoot("Brave (Flatpak)", home.resolve(".var/app/com.brave.Browser/config/BraveSoftware/Brave-Browser"), "Brave"),
            ChromiumRoot("Chromium (Snap)", home.resolve("snap/chromium/common/chromium"), "Chromium"),
        )
    }

    private fun geckoRoots(): List<Pair<String, Path>> = when {
        macOS -> listOf(
            "Firefox" to home.resolve("Library/Application Support/Firefox"),
            "Zen" to home.resolve("Library/Application Support/zen"),
            "LibreWolf" to home.resolve("Library/Application Support/LibreWolf"),
        )
        windows -> {
            val roaming = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) } ?: home.resolve("AppData/Roaming")
            listOf(
                "Firefox" to roaming.resolve("Mozilla/Firefox"),
                "Zen" to roaming.resolve("zen"),
                "LibreWolf" to roaming.resolve("librewolf"),
                "Firefox (Microsoft Store)" to home.resolve("AppData/Local/Packages/Mozilla.Firefox_n80bbvh6b1yt2/LocalCache/Roaming/Mozilla/Firefox"),
            )
        }
        else -> {
            val config = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let { Paths.get(it) } ?: home.resolve(".config")
            listOf(
                "Firefox" to config.resolve("mozilla/firefox"),
                "Firefox" to home.resolve(".mozilla/firefox"),
                "Zen" to config.resolve("zen"),
                "Zen" to home.resolve(".zen"),
                "LibreWolf" to config.resolve("librewolf"),
                "LibreWolf" to home.resolve(".librewolf"),
                "Firefox (Flatpak)" to home.resolve(".var/app/org.mozilla.firefox/config/mozilla/firefox"),
                "Firefox (Flatpak)" to home.resolve(".var/app/org.mozilla.firefox/.mozilla/firefox"),
                "Firefox (Snap)" to home.resolve("snap/firefox/common/.mozilla/firefox"),
                "Zen (Flatpak)" to home.resolve(".var/app/app.zen_browser.zen/.zen"),
                "Zen (Flatpak)" to home.resolve(".var/app/app.zen_browser.zen/config/zen"),
                "LibreWolf (Flatpak)" to home.resolve(".var/app/io.gitlab.librewolf-community/config/librewolf"),
                "LibreWolf (Flatpak)" to home.resolve(".var/app/io.gitlab.librewolf-community/.librewolf"),
            )
        }
    }

    private fun childDirectories(root: Path): List<Path> = try {
        if (Files.isDirectory(root)) Files.list(root).use { entries -> entries.filter { Files.isDirectory(it) }.limit(200).toList() } else emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    private fun installedMacBrowser(browser: String): Boolean {
        if (!macOS) return false
        val bundle = when (browser) {
            "Chrome" -> "Google Chrome.app"
            "Chrome Beta" -> "Google Chrome Beta.app"
            "Chrome Canary" -> "Google Chrome Canary.app"
            "Chrome Dev" -> "Google Chrome Dev.app"
            "Chromium" -> "Chromium.app"
            "Brave" -> "Brave Browser.app"
            "Microsoft Edge" -> "Microsoft Edge.app"
            "Vivaldi" -> "Vivaldi.app"
            "Opera" -> "Opera.app"
            "Arc" -> "Arc.app"
            "Firefox" -> "Firefox.app"
            "Zen" -> "Zen.app"
            "LibreWolf" -> "LibreWolf.app"
            "Safari" -> "Safari.app"
            else -> return false
        }
        return applicationDirectories.any { Files.isDirectory(it.resolve(bundle)) }
    }

    private fun cookieFilePresent(path: Path): Boolean = try {
        Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java).isRegularFile
    } catch (_: java.nio.file.NoSuchFileException) {
        false
    } catch (_: AccessDeniedException) {
        true // Preserve the source so inspect() can distinguish denied access from no cookies.
    } catch (_: SecurityException) {
        true
    } catch (error: java.nio.file.FileSystemException) {
        error.reason.orEmpty().contains("permitted", ignoreCase = true) || error.reason.orEmpty().contains("denied", ignoreCase = true)
    }

    private fun parseIni(lines: List<String>): List<Map<String, String>> {
        val profiles = mutableListOf<MutableMap<String, String>>()
        var current: MutableMap<String, String>? = null
        lines.forEach { raw ->
            val line = raw.trim().removePrefix("\uFEFF")
            when {
                line.startsWith('[') -> {
                    current = if (line.startsWith("[Profile") && line.endsWith(']')) mutableMapOf<String, String>().also { profiles += it } else null
                }
                line.isNotEmpty() && !line.startsWith(';') && !line.startsWith('#') && '=' in line -> {
                    current?.set(line.substringBefore('=').trim(), line.substringAfter('=').trim())
                }
            }
        }
        return profiles
    }

    private fun id(source: Source): String = MessageDigest.getInstance("SHA-256")
        .digest("${source.browser}:${source.path.toAbsolutePath().normalize()}".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private enum class Kind { CHROMIUM, GECKO, SAFARI }
    private class Source(val browser: String, val name: String, val path: Path, val kind: Kind, val keyring: String = "")
    private data class ChromiumRoot(val browser: String, val path: Path, val keyring: String)
}
