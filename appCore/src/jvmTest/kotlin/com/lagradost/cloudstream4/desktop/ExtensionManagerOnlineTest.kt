package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream4.desktop.plugins.ExtensionManager
import com.lagradost.cloudstream4.desktop.plugins.PluginStatus
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.first
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end: add a repository, install an extension from GitHub, and load it.
 * Uses upstream's TestPlugins list. Skipped when GitHub can't be reached.
 */
class ExtensionManagerOnlineTest {
    private val pluginList = "https://raw.githubusercontent.com/recloudstream/TestPlugins/builds/plugins.json"

    private fun online() = runCatching {
        (URI(pluginList).toURL().openConnection().apply { connectTimeout = 3000 }).getInputStream().close()
    }.isSuccess

    @Test
    fun addRepositoryInstallAndLoad() = runBlocking {
        org.junit.Assume.assumeTrue("GitHub not reachable", online())
        System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-online").toString())

        // TestPlugins only publishes a plugin list, so serve a repo.json that points at it
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            createContext("/repo.json") { ex ->
                val body = """{"name":"TestPlugins","manifestVersion":1,"pluginLists":["$pluginList"]}""".toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            ExtensionManager.start()
            val repoUrl = "http://127.0.0.1:${server.address.port}/repo.json"
            ExtensionManager.addRepository(repoUrl).join()

            val repo = withTimeout(30_000) {
                ExtensionManager.state.first { s -> s.repositories.any { it.data.url == repoUrl && it.plugins.isNotEmpty() } }
            }.repositories.first { it.data.url == repoUrl }
            println("Repository lists: ${repo.plugins.map { it.name }}")

            ExtensionManager.install(repoUrl, repo.plugins.first()).join()
            val state = ExtensionManager.state.value
            val installed = state.installed.single()
            val status = state.status[installed.filePath]
            println("Installed ${installed.name}: $status")
            assertTrue(status is PluginStatus.Loaded, "status was $status")
            assertTrue(ExtensionManager.providers().any { it.name == "Example provider" })
        } finally {
            server.stop(0)
        }
    }
}
