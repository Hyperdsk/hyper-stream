package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.plugins.PluginLoader
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Loads every .cs3 under CS_COMPAT_DIR and reports which load. Skipped when the variable is not
 * set, since it needs real extensions. Example:
 *   CS_COMPAT_DIR="$HOME/Library/Application Support/CloudStream/Extensions" ./gradlew :desktopApp:jvmTest --tests '*Compatibility*'
 */
class ExtensionCompatibilityTest {
    @Test
    fun loadsInstalledExtensions() {
        val dir = System.getenv("CS_COMPAT_DIR")?.let(::File)
        // Reported as skipped, not passed, when there's nothing to check
        org.junit.Assume.assumeTrue("CS_COMPAT_DIR not set", dir != null && dir.isDirectory)
        dir!!
        System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-compat").toString())
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "cs3" }.toList()
        val failed = mutableListOf<String>()
        for (file in files) {
            val line = runCatching { PluginLoader.load(file) }.fold(
                onSuccess = {
                    val text = "OK    ${file.nameWithoutExtension}: ${it.providerNames} placeholders=${it.stubbed.size}"
                    PluginLoader.unload(file)
                    text
                },
                onFailure = { e ->
                    failed += file.name
                    val root = generateSequence(e) { it.cause }.last()
                    val frames = root.stackTrace.take(8).joinToString("\n") { "        at $it" }
                    "FAIL  ${file.nameWithoutExtension}: ${e.message}\n$frames"
                },
            )
            println(line)
        }
        println("Loaded ${files.size - failed.size} of ${files.size}")
        assertTrue(failed.isEmpty(), "Failed: $failed")
    }
}
