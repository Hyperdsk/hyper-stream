package com.lagradost.cloudstream4.desktop

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lagradost.cloudstream4.desktop.data.Bookmark
import com.lagradost.cloudstream4.desktop.data.HistoryEntry
import com.lagradost.cloudstream4.desktop.data.LibraryStore
import com.lagradost.cloudstream4.desktop.data.WatchType
import com.lagradost.cloudstream4.desktop.data.jsonMapper
import com.lagradost.cloudstream4.desktop.sync.SyncManager
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Sync against a fake Supabase: the same endpoints (sign-in, hs_push, hs_sync_items) and the same
 * rule as the real hs_push function, that a change only replaces a newer-or-equal one.
 */
class SyncManagerTest {
    private data class Row(val kind: String, val key: String, val data: JsonNode?, val deleted: Boolean, val at: Long, var updatedAt: String)

    private val rows = mutableMapOf<Pair<String, String>, Row>()
    private var clock = 0L
    private fun serverTime() = Instant.ofEpochMilli(1_800_000_000_000 + clock++).toString()

    private fun HttpExchange.json(value: Any, code: Int = 200) {
        val bytes = jsonMapper.writeValueAsBytes(value)
        responseHeaders.set("Content-Type", "application/json")
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun server() = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/auth/v1/otp") { it.json(emptyMap<String, Any>()) }
        createContext("/auth/v1/recover") { it.json(emptyMap<String, Any>()) }
        createContext("/auth/v1/verify") { ex ->
            ex.json(mapOf("access_token" to "token", "refresh_token" to "refresh", "expires_in" to 3600, "user" to mapOf("id" to "user-1", "email" to "me@example.com")))
        }
        createContext("/auth/v1/user") { ex -> ex.json(mapOf("id" to "user-1", "email" to "me@example.com")) }
        createContext("/auth/v1/signup") { ex ->
            ex.json(mapOf("access_token" to "token", "refresh_token" to "refresh", "expires_in" to 3600, "user" to mapOf("id" to "user-2", "email" to "new@example.com")))
        }
        createContext("/rest/v1/rpc/hs_push") { ex ->
            val items = jsonMapper.readTree(ex.requestBody)["items"]
            synchronized(rows) {
                items.forEach { i ->
                    val key = i["kind"].asText() to i["key"].asText()
                    val at = i["at"].asLong()
                    val old = rows[key]
                    if (old == null || at > old.at) {
                        rows[key] = Row(key.first, key.second, i["data"]?.takeUnless { it.isNull }, i["deleted"].asBoolean(), at, serverTime())
                    }
                }
            }
            ex.json(1)
        }
        createContext("/rest/v1/hs_sync_items") { ex ->
            val since = ex.requestURI.query.split('&').firstOrNull { it.startsWith("updated_at=gte.") }?.removePrefix("updated_at=gte.")
                ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
            val list = synchronized(rows) { rows.values.filter { since == null || it.updatedAt >= since }.sortedBy { it.updatedAt } }
            ex.json(list.map { mapOf("kind" to it.kind, "item_key" to it.key, "data" to it.data, "deleted" to it.deleted, "client_updated_at" to it.at, "updated_at" to it.updatedAt) })
        }
        start()
    }

    private fun remote(kind: String, key: String, value: Any?, at: Long, deleted: Boolean = false) = synchronized(rows) {
        rows[kind to key] = Row(kind, key, value?.let { jsonMapper.valueToTree<ObjectNode>(it) }, deleted, at, serverTime())
    }

    @Test
    fun pushesPullsAndKeepsTheNewerChange() = runBlocking {
        val server = server()
        System.setProperty("hyperstream.syncUrl", "http://127.0.0.1:${server.address.port}")
        System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-sync").toString())
        try {
            // Sign in with a code
            SyncManager.sendLink("me@example.com").join()
            SyncManager.verify("123456").join()
            withTimeout(5_000) { SyncManager.ui.first { it.signedIn } }

            // Local progress goes up
            val episode = HistoryEntry("show", "Prov", "Show", null, null, "ep1", null, 1, 1, 60_000, 1_200_000, updatedAt = 1_000)
            LibraryStore.saveProgress(episode)
            SyncManager.syncNow()
            val pushed = synchronized(rows) { rows["history" to "Prov|ep1"] }
            assertNotNull(pushed, "progress was pushed")
            assertEquals(60_000, pushed.data!!["positionMs"].asLong())
            assertTrue(synchronized(rows) { rows.containsKey("settings" to "app") }, "settings were pushed")

            // Another device bookmarks a title and watches further
            remote("bookmark", "Prov|other", Bookmark("other", "Prov", "Other", null, null, null, WatchType.WATCHING, updatedAt = 2_000), at = 2_000)
            remote("history", "Prov|ep1", episode.copy(positionMs = 300_000, updatedAt = 3_000), at = 3_000)
            SyncManager.syncNow()
            val library = LibraryStore.state.value
            assertNotNull(library.bookmark("Prov", "other"), "the other device's bookmark arrived")
            assertEquals(300_000, library.progress("Prov", "ep1")?.positionMs, "the newer progress won")

            // An older change from elsewhere doesn't overwrite newer progress here
            LibraryStore.saveProgress(episode.copy(positionMs = 400_000, updatedAt = 5_000))
            remote("history", "Prov|ep1", episode.copy(positionMs = 100_000, updatedAt = 4_000), at = 4_000)
            SyncManager.syncNow()
            assertEquals(400_000, LibraryStore.state.value.progress("Prov", "ep1")?.positionMs, "newer local progress kept")
            assertEquals(400_000, synchronized(rows) { rows["history" to "Prov|ep1"] }!!.data!!["positionMs"].asLong(), "and sent")

            // The same settings read back with their keys in another order (as Postgres stores them)
            // aren't a change, so nothing is sent back
            val stored = synchronized(rows) { rows["settings" to "app"]!! }
            val reordered = jsonMapper.createObjectNode().apply {
                stored.data!!.fieldNames().asSequence().toList().reversed().forEach { set<JsonNode>(it, stored.data.get(it)) }
            }
            synchronized(rows) { rows["settings" to "app"] = stored.copy(data = reordered, at = stored.at + 1, updatedAt = serverTime()) }
            SyncManager.syncNow()
            SyncManager.syncNow()
            assertEquals(stored.at + 1, synchronized(rows) { rows["settings" to "app"]!! }.at, "unchanged settings weren't sent again")

            // A removal on another device removes it here
            remote("bookmark", "Prov|other", null, at = 6_000, deleted = true)
            SyncManager.syncNow()
            assertNull(LibraryStore.state.value.bookmark("Prov", "other"), "the bookmark removed elsewhere is gone")

            // A removal here reaches the server
            LibraryStore.setBookmark(Bookmark("mine", "Prov", "Mine", null, null, null, WatchType.PLANTOWATCH), "Prov", "mine")
            SyncManager.syncNow()
            assertTrue(synchronized(rows) { rows["bookmark" to "Prov|mine"] }?.deleted == false)
            LibraryStore.setBookmark(null, "Prov", "mine")
            SyncManager.syncNow()
            assertTrue(synchronized(rows) { rows["bookmark" to "Prov|mine"] }?.deleted == true, "the removal was sent")

            SyncManager.signOut().join()
            assertTrue(!SyncManager.ui.value.signedIn)

            // An account with a password, for people who can't get emails
            SyncManager.signInWithPassword("new@example.com", "secret1", create = true).join()
            withTimeout(5_000) { SyncManager.ui.first { it.signedIn } }
            assertEquals("new@example.com", SyncManager.ui.value.email)
            SyncManager.signOut().join()

            // A forgotten password: the reset link signs in, then the app asks for a new one
            SyncManager.sendPasswordReset("me@example.com").join()
            assertEquals("me@example.com", SyncManager.ui.value.resetSentTo)
            SyncManager.completeRedirect("hyperstream://auth#access_token=abc&refresh_token=def&expires_in=3600&token_type=bearer&type=recovery")
            assertTrue(SyncManager.ui.value.signedIn && SyncManager.ui.value.choosingPassword, "asks for a new password")
            // Made up per run: the fake server accepts any password
            SyncManager.setPassword(java.util.UUID.randomUUID().toString()).join()
            assertTrue(!SyncManager.ui.value.choosingPassword && SyncManager.ui.value.error == null, "the new password was saved")
            SyncManager.signOut().join()
            // An ordinary sign-in link doesn't ask
            SyncManager.completeRedirect("hyperstream://auth#access_token=abc&refresh_token=def&expires_in=3600&type=magiclink")
            assertTrue(SyncManager.ui.value.signedIn && !SyncManager.ui.value.choosingPassword)
            SyncManager.signOut().join()

            // Opening the emailed link on the Mac: it returns to the app's local page, which hands over the session
            com.lagradost.cloudstream4.desktop.sync.SignInRedirect.prepare()
            val page = com.lagradost.cloudstream4.desktop.sync.SignInRedirect.url
            if (page != null) {
                val html = java.net.URI(page).toURL().readText()
                assertTrue("Signing in to Hyper Stream" in html)
                val done = java.net.URI("$page/done?access_token=abc&refresh_token=def&expires_in=3600&token_type=bearer&type=magiclink").toURL().readText()
                assertEquals("ok", done)
                withTimeout(5_000) { SyncManager.ui.first { it.signedIn } }
                assertEquals("me@example.com", SyncManager.ui.value.email)
                SyncManager.signOut().join()
            } else {
                println("Port 53682 is in use (Hyper Stream running?), skipped the link page check")
            }
        } finally {
            server.stop(0)
            System.clearProperty("hyperstream.syncUrl")
        }
    }
}
