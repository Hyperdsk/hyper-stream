package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.data.AppDirs
import com.lagradost.cloudstream4.desktop.plugins.ExtensionManager
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** An extension's name comes from its repository's list, so it must not choose where the file goes. */
class ExtensionInstallPathTest {
    @Test
    fun namesThatLeaveTheRepositoryFolderAreRefused() {
        // Only used if no other test set up the data folder first
        if (System.getProperty("cloudstream.dataDir") == null) {
            System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-ext-path").toString())
        }
        val repo = "https://example.com/repo.json"
        val folder = ExtensionManager.pluginFile(repo, "Good").parentFile

        val ok = ExtensionManager.pluginFile(repo, "Good")
        assertEquals("Good.cs3", ok.name)
        assertEquals(AppDirs.extensions.canonicalFile, ok.canonicalFile.parentFile.parentFile)
        // Ordinary names with dots, dashes and spaces still work
        assertEquals(folder, ExtensionManager.pluginFile(repo, "My-Provider v2.1").parentFile)

        listOf(
            "../Other",
            "../raw.githubusercontent.com_someone_repo.json/Theirs",
            "../../../Evil",
            "a/b",
            "a\\b",
            "..",
            ".",
            "",
            " ",
            ".hidden",
            "C:Evil",
            "bad\u0000name",
            "x".repeat(200),
        ).forEach { name ->
            assertFailsWith<IOException>("\"$name\" should be refused") { ExtensionManager.pluginFile(repo, name) }
        }
    }
}
