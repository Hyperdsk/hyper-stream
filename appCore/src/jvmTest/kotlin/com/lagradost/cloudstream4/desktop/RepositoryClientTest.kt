package com.lagradost.cloudstream4.desktop

import com.lagradost.cloudstream3.plugins.Repository
import com.lagradost.cloudstream3.plugins.SitePlugin
import com.lagradost.cloudstream4.desktop.data.jsonMapper
import com.lagradost.cloudstream4.desktop.plugins.ExtensionManager
import com.lagradost.cloudstream4.desktop.plugins.RepositoryClient
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Reading repositories: plugin lists merged without duplicates, and no quiet switch to plain http. */
class RepositoryClientTest {
    private fun plugin(name: String, version: Int) = mapOf(
        "url" to "http://127.0.0.1/$name.cs3", "status" to 1, "version" to version, "apiVersion" to 1,
        "name" to name, "internalName" to name, "authors" to emptyList<String>(),
    )

    @Test
    fun anExtensionInSeveralListsIsListedOnceAtItsNewestVersion() = runBlocking {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
            fun serve(path: String, body: Any) = createContext(path) { ex ->
                val bytes = jsonMapper.writeValueAsBytes(body)
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            serve("/stable.json", listOf(plugin("Foo", 1), plugin("Bar", 1)))
            serve("/pre.json", listOf(plugin("Foo", 2)))
            start()
        }
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val repo = Repository(null, "Two lists", null, 1, listOf("$base/stable.json", "$base/pre.json"))
            val plugins = RepositoryClient.fetchRepoPlugins(repo, "$base/repo.json")
            assertEquals(listOf("Foo", "Bar"), plugins.map { it.internalName })
            assertEquals(2, plugins.first { it.internalName == "Foo" }.version)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun aSecureRepositoryCantHandOutPlainHttp() = runBlocking {
        assertTrue(RepositoryClient.downgrades("https://example.com/repo.json", "http://example.com/Foo.cs3"))
        assertFalse(RepositoryClient.downgrades("https://example.com/repo.json", "https://cdn.example.com/Foo.cs3"))
        // A repository the user chose to add over http
        assertFalse(RepositoryClient.downgrades("http://example.com/repo.json", "http://example.com/Foo.cs3"))

        // Its plugin list over http is refused before anything is fetched
        val repo = Repository(null, "Secure", null, 1, listOf("http://127.0.0.1:1/plugins.json"))
        val error = assertFailsWith<IOException> { RepositoryClient.fetchRepoPlugins(repo, "https://example.com/repo.json") }
        assertTrue("plain http" in error.message.orEmpty(), error.message)

        // And so is an extension download over http
        if (System.getProperty("cloudstream.dataDir") == null) {
            System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-repo-client").toString())
        }
        val download = jsonMapper.convertValue(plugin("Insecure", 1), SitePlugin::class.java)
        ExtensionManager.install("https://example.com/repo.json", download).join()
        val message = ExtensionManager.state.value.message.orEmpty()
        assertTrue("plain http" in message, message)
        assertTrue(ExtensionManager.state.value.installed.none { it.internalName == "Insecure" })
        ExtensionManager.dismissMessage()
    }
}
