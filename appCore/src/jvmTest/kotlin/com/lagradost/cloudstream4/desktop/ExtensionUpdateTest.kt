package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.plugins.SitePlugin
import com.lagradost.cloudstream4.desktop.plugins.ExtensionManager
import com.lagradost.cloudstream4.desktop.plugins.PluginLoader
import com.lagradost.cloudstream4.desktop.plugins.PluginStatus
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An update that fails to download leaves the installed version loaded and working. Runs offline. */
class ExtensionUpdateTest {
    @Test
    fun aFailedUpdateKeepsTheInstalledVersionLoaded() = runBlocking {
        // Only used if no other test set up the data folder first
        if (System.getProperty("cloudstream.dataDir") == null) {
            System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-ext-update").toString())
        }
        // Upstream's template extension (recloudstream/TestPlugins), as PluginLoaderTest uses
        val cs3 = javaClass.getResourceAsStream("/ExampleProvider.cs3")!!.use { it.readBytes() }
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/good.cs3") { ex ->
                ex.sendResponseHeaders(200, cs3.size.toLong())
                ex.responseBody.use { it.write(cs3) }
            }
            // A bot check page instead of the file
            createContext("/page.cs3") { ex ->
                val page = "<!DOCTYPE html><html><body>Checking your browser</body></html>".toByteArray()
                ex.responseHeaders.set("Content-Type", "text/html")
                ex.sendResponseHeaders(200, page.size.toLong())
                ex.responseBody.use { it.write(page) }
            }
            start()
        }
        val base = "http://127.0.0.1:${server.address.port}"
        val repoUrl = "$base/repo.json"
        fun plugin(url: String, version: Int, hash: String? = null) = SitePlugin(
            url = url, status = 1, version = version, apiVersion = 1, name = "Update Test", internalName = "UpdateTest",
            authors = emptyList(), description = null, repositoryUrl = null, tvTypes = null, language = null,
            iconUrl = null, fileSize = null, fileHash = hash,
        )
        try {
            ExtensionManager.install(repoUrl, plugin("$base/good.cs3", 1)).join()
            val installed = ExtensionManager.state.value.installed.first { it.repositoryUrl == repoUrl }
            val file = File(installed.filePath)
            val loaded = ExtensionManager.state.value.status[installed.filePath]
            assertTrue(loaded is PluginStatus.Loaded, "installed: $loaded")
            assertTrue(PluginLoader.isLoaded(file))

            // Missing on the host, a web page, and a file whose hash doesn't match the list's
            listOf(
                plugin("$base/missing.cs3", 2),
                plugin("$base/page.cs3", 2),
                plugin("$base/good.cs3", 2, hash = "sha256-" + "0".repeat(64)),
            ).forEach { update ->
                ExtensionManager.install(repoUrl, update).join()
                val state = ExtensionManager.state.value
                assertTrue(PluginLoader.isLoaded(file), "still loaded after a failed update from ${update.url}")
                assertTrue(APIHolder.allProviders.any { it.sourcePlugin == file.absolutePath }, "its providers are still there")
                assertTrue(state.status[installed.filePath] is PluginStatus.Loaded, "and shown as loaded: ${state.status[installed.filePath]}")
                assertEquals(1, state.installed.first { it.repositoryUrl == repoUrl }.version, "the installed version is unchanged")
                assertTrue(state.message.orEmpty().startsWith("Could not install"), "the failure is reported: ${state.message}")
                assertTrue(file.parentFile.listFiles().orEmpty().none { it.name.endsWith(".tmp") }, "no temp files left")
            }

            // A good update still replaces it
            ExtensionManager.install(repoUrl, plugin("$base/good.cs3", 2)).join()
            assertEquals(2, ExtensionManager.state.value.installed.first { it.repositoryUrl == repoUrl }.version)
            assertTrue(PluginLoader.isLoaded(file))
        } finally {
            // Other tests expect no extensions left installed
            ExtensionManager.state.value.installed.filter { it.repositoryUrl == repoUrl }.forEach { ExtensionManager.uninstall(it).join() }
            ExtensionManager.dismissMessage()
            server.stop(0)
        }
    }
}
