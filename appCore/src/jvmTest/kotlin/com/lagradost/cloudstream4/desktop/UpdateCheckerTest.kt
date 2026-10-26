package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.data.jsonMapper
import com.lagradost.cloudstream4.desktop.update.UpdateChecker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class UpdateCheckerTest {
    private val releases = jsonMapper.readTree(
        """
        [
          {
            "tag_name": "v1.2.1-android",
            "name": "Hyper Stream 1.2.1 (Android)",
            "body": "Android only",
            "html_url": "https://github.com/Hyperdsk/hyper-stream/releases/tag/v1.2.1-android",
            "draft": false,
            "prerelease": false,
            "assets": [
              {
                "name": "HyperStream-1.2.1-android-and-tv.apk",
                "size": 100,
                "browser_download_url": "https://github.com/Hyperdsk/hyper-stream/releases/download/v1.2.1-android/HyperStream-1.2.1-android-and-tv.apk",
                "digest": "sha256:abcd"
              }
            ]
          },
          {
            "tag_name": "v1.2.0",
            "name": "Hyper Stream 1.2.0",
            "body": "All platforms",
            "html_url": "https://github.com/Hyperdsk/hyper-stream/releases/tag/v1.2.0",
            "draft": false,
            "prerelease": false,
            "assets": [
              { "name": "HyperStream-1.2.0-macos-apple-silicon.dmg", "size": 200, "browser_download_url": "https://github.com/x/arm.dmg" },
              { "name": "HyperStream-1.2.0-macos-intel.zip", "size": 201, "browser_download_url": "https://github.com/x/intel.zip" },
              { "name": "HyperStream-1.2.0-windows-x64.msi", "size": 300, "browser_download_url": "https://github.com/x/win.msi" },
              { "name": "HyperStream-1.2.0-linux-x64.deb", "size": 400, "browser_download_url": "https://github.com/x/linux.deb" },
              { "name": "HyperStream-1.2.0-android-and-tv.apk", "size": 500, "browser_download_url": "https://github.com/x/app.apk" }
            ]
          },
          {
            "tag_name": "v1.3.0-beta",
            "name": "Beta",
            "body": "Not ready",
            "html_url": "https://github.com/x/beta",
            "draft": false,
            "prerelease": true,
            "assets": [
              { "name": "HyperStream-1.3.0-android-and-tv.apk", "size": 600, "browser_download_url": "https://github.com/x/beta.apk" }
            ]
          },
          {
            "tag_name": "v1.3.1",
            "name": "Draft",
            "body": "Not published",
            "html_url": "https://github.com/x/draft",
            "draft": true,
            "prerelease": false,
            "assets": [
              { "name": "HyperStream-1.3.1-linux-x64.deb", "size": 700, "browser_download_url": "https://github.com/x/draft.deb" }
            ]
          }
        ]
        """.trimIndent()
    )

    @Test
    fun removesInstallersOnceTheirVersionIsInstalled() {
        val updates = java.nio.file.Files.createTempDirectory("hs-updates").toFile()
        listOf("1.2.4", "1.2.5", "1.3.0").forEach { java.io.File(updates, "$it/HyperStream-$it.apk").apply { parentFile.mkdirs(); writeText("apk") } }
        UpdateChecker.removeInstalledDownloads(updates, current = "1.2.5")
        assertEquals(listOf("1.3.0"), updates.list()!!.toList(), "only the not yet installed update is kept")
        updates.deleteRecursively()
    }

    @Test
    fun acceptsDownloadsFromThisRepositoryOnly() {
        assertTrue(UpdateChecker.isOwnDownload("https://github.com/Hyperdsk/hyper-stream/releases/download/v1.2.5/HyperStream-1.2.5-android-and-tv.apk"))
        assertFalse(UpdateChecker.isOwnDownload("https://github.com/someone/hyper-stream/releases/download/v1.2.5/HyperStream-1.2.5-android-and-tv.apk"))
        assertFalse(UpdateChecker.isOwnDownload("https://github.com/Hyperdsk/hyper-stream.evil.com/releases/download/x.apk"))
    }

    @Test
    fun picksTheNewestReleaseForEachPlatform() {
        val android = UpdateChecker.compatibleReleases(releases, UpdateTarget.Android)
        assertEquals("1.2.1", UpdateChecker.newestRelease(android)?.version)

        for (target in listOf(
            UpdateTarget.MacAppleSilicon,
            UpdateTarget.MacIntel,
            UpdateTarget.Windows,
            UpdateTarget.Linux,
        )) {
            val matched = UpdateChecker.compatibleReleases(releases, target)
            assertEquals("1.2.0", UpdateChecker.newestRelease(matched)?.version, target.name)
        }
    }

    @Test
    fun matchesEachPlatformsAsset() {
        assertTrue(
            UpdateChecker.newestRelease(UpdateChecker.compatibleReleases(releases, UpdateTarget.Android))!!
                .asset.name.endsWith(".apk")
        )
        assertEquals(
            "HyperStream-1.2.0-macos-apple-silicon.dmg",
            UpdateChecker.compatibleReleases(releases, UpdateTarget.MacAppleSilicon).single().asset.name,
        )
        assertEquals(
            "HyperStream-1.2.0-macos-intel.zip",
            UpdateChecker.compatibleReleases(releases, UpdateTarget.MacIntel).single().asset.name,
        )
        assertEquals(
            "HyperStream-1.2.0-windows-x64.msi",
            UpdateChecker.compatibleReleases(releases, UpdateTarget.Windows).single().asset.name,
        )
        assertEquals(
            "HyperStream-1.2.0-linux-x64.deb",
            UpdateChecker.compatibleReleases(releases, UpdateTarget.Linux).single().asset.name,
        )
    }

    @Test
    fun readsVersionsWithAnAndroidSuffix() {
        assertEquals("1.2.1", UpdateChecker.versionFromTag("v1.2.1-android"))
        assertEquals("1.2.1", UpdateChecker.versionFromTag("1.2.1"))
        assertEquals(null, UpdateChecker.versionFromTag("pre-release"))
    }

    @Test
    fun comparesVersionsNumerically() {
        assertTrue(UpdateChecker.isNewer("1.2.1", "1.2.0"))
        assertTrue(UpdateChecker.isNewer("1.10.0", "1.9.9"))
        assertFalse(UpdateChecker.isNewer("1.2.0", "1.2.0"))
        assertFalse(UpdateChecker.isNewer("1.2.0", "1.2.1"))
        assertTrue(UpdateChecker.isNewer("1.2.0", "dev build"))
    }

    @Test
    fun ignoresReleasesWithoutAnAssetForThisPlatform() {
        val androidOnly = jsonMapper.readTree(
            """
            [
              {
                "tag_name": "v1.2.1-android",
                "draft": false,
                "prerelease": false,
                "assets": [
                  { "name": "HyperStream-1.2.1-android-and-tv.apk", "size": 1, "browser_download_url": "https://github.com/x/app.apk" }
                ]
              }
            ]
            """.trimIndent()
        )
        assertTrue(UpdateChecker.compatibleReleases(androidOnly, UpdateTarget.Linux).isEmpty())
        assertNotNull(UpdateChecker.newestRelease(UpdateChecker.compatibleReleases(androidOnly, UpdateTarget.Android)))
    }
}
