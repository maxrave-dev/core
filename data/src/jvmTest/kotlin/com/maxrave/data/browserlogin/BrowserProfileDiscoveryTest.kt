package com.maxrave.data.browserlogin

import com.maxrave.domain.repository.BrowserProfileStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Every search root is a synthetic home/application directory; no real browser is inspected. */
class BrowserProfileDiscoveryTest {
    @Test
    fun installedChromeIsNotHiddenWhenNoCookieFileCanBeDiscovered() = fixture { home, applications ->
        Files.createDirectories(applications.resolve("Google Chrome.app"))
        val repository = repository(home, applications)
        val profiles = runBlocking { repository.profiles() }
        assertEquals(listOf("Chrome"), profiles.map { it.browser })
        assertEquals(BrowserProfileStatus.NO_COOKIES, profiles.single().status)
    }

    @Test
    fun existingDefaultProfileIsListedBeforeItsFirstYouTubeCookie() = fixture { home, applications ->
        Files.createDirectories(applications.resolve("Google Chrome.app"))
        Files.createDirectories(home.resolve("Library/Application Support/Google/Chrome/Default"))
        val repository = repository(home, applications)
        val profiles = runBlocking { repository.profiles() }
        assertEquals("Default", profiles.single().name)
        assertEquals(BrowserProfileStatus.NO_COOKIES, profiles.single().status)
    }

    @Test
    fun localStateProfilesDoNotDependOnSuccessfulCookieOrDirectoryDiscovery() = fixture { home, applications ->
        Files.createDirectories(applications.resolve("Google Chrome.app"))
        val root = Files.createDirectories(home.resolve("Library/Application Support/Google/Chrome"))
        Files.writeString(root.resolve("Local State"), """{"profile":{"info_cache":{"Work":{"name":"Work profile"}}}}""")
        val repository = repository(home, applications)
        val profiles = runBlocking { repository.profiles() }
        assertEquals(listOf("Work profile"), profiles.map { it.name })
        assertEquals(BrowserProfileStatus.NO_COOKIES, profiles.single().status)
    }

    @Test
    fun absentChromeIsNotInvented() = fixture { home, applications ->
        val repository = repository(home, applications)
        assertTrue(runBlocking { repository.profiles() }.isEmpty())
    }

    @Test
    fun leftoverChromiumFoldersDoNotInventUninstalledBrowsers() = fixture { home, applications ->
        val support = home.resolve("Library/Application Support")
        listOf("Google/Chrome", "Google/Chrome Beta", "Google/Chrome Canary", "Google/Chrome Dev", "Chromium", "BraveSoftware/Brave-Browser", "Microsoft Edge", "Vivaldi", "com.operasoftware.Opera", "Arc/User Data").forEach {
            Files.createDirectories(support.resolve(it).resolve("Default"))
        }
        Files.createDirectories(applications.resolve("Google Chrome.app"))
        val profiles = runBlocking { repository(home, applications).profiles() }
        assertEquals(setOf("Chrome"), profiles.map { it.browser }.toSet())
    }

    @Test
    fun leftoverGeckoProfilesDoNotInventUninstalledBrowsers() = fixture { home, applications ->
        val support = home.resolve("Library/Application Support")
        listOf("Firefox", "zen", "LibreWolf").forEach { folder ->
            val directory = Files.createDirectories(support.resolve(folder))
            Files.writeString(directory.resolve("profiles.ini"), "[Profile0]\nName=Default\nIsRelative=1\nPath=Profiles/default\n")
        }
        Files.createDirectories(applications.resolve("Zen.app"))
        val profiles = runBlocking { repository(home, applications).profiles() }
        assertEquals(setOf("Zen"), profiles.map { it.browser }.toSet())
    }

    private fun repository(home: Path, applications: Path) = BrowserLoginRepositoryImpl(
        home, "Mac OS X", listOf(applications), BrowserCookieSnapshot(home.resolve("snapshots")),
    )

    private fun fixture(block: (Path, Path) -> Unit) {
        val root = Files.createTempDirectory("browser-profile-fixture-")
        try {
            block(Files.createDirectory(root.resolve("home")), Files.createDirectory(root.resolve("applications")))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
