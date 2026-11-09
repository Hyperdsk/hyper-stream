package com.lagradost.cloudstream4.desktop

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.lagradost.cloudstream4.desktop.data.Bookmark
import com.lagradost.cloudstream4.desktop.data.HistoryEntry
import com.lagradost.cloudstream4.desktop.data.LibraryStore
import com.lagradost.cloudstream4.desktop.data.WatchType
import com.lagradost.cloudstream4.desktop.data.jsonMapper
import com.lagradost.cloudstream4.desktop.plugins.ExtensionManager
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.cloudstream4.desktop.sync.SyncManager
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.delay
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
    /** Downloads of an extension whose file is gone from its host. */
    private val brokenDownloads = java.util.concurrent.atomic.AtomicInteger()

    /** Runs once a page has been read, before it is sent: lets a test change rows mid-pull. */
    @Volatile private var afterPage: (() -> Unit)? = null
    private var clock = 0L
    private fun serverTime() = Instant.ofEpochMilli(1_800_000_000_000 + clock++).toString()

    private fun HttpExchange.json(value: Any, code: Int = 200) {
        val bytes = jsonMapper.writeValueAsBytes(value)
        responseHeaders.set("Content-Type", "application/json")
        sendResponseHeaders(code, bytes.size.toLong())
        responseBody.use { it.write(bytes) }
    }

    private fun server() = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        // An extension repository whose one extension can't be downloaded
        createContext("/repo/repo.json") { ex ->
            ex.json(mapOf("name" to "Ext Repo", "manifestVersion" to 1, "pluginLists" to listOf("http://127.0.0.1:${address.port}/repo/plugins.json")))
        }
        createContext("/repo/plugins.json") { ex ->
            ex.json(listOf(mapOf(
                "url" to "http://127.0.0.1:${address.port}/repo/Broken.cs3", "status" to 1, "version" to 1, "apiVersion" to 1,
                "name" to "Broken", "internalName" to "Broken", "authors" to emptyList<String>(),
            )))
        }
        createContext("/repo/Broken.cs3") { ex ->
            brokenDownloads.incrementAndGet()
            ex.json(mapOf("error" to "gone"), code = 404)
        }
        createContext("/auth/v1/otp") { it.json(emptyMap<String, Any>()) }
        createContext("/auth/v1/recover") { it.json(emptyMap<String, Any>()) }
        // As Supabase answers a refresh token from a session that was signed out
        createContext("/auth/v1/token") { ex ->
            ex.json(mapOf("code" to 400, "error_code" to "refresh_token_not_found", "msg" to "Invalid Refresh Token: Refresh Token Not Found"), code = 400)
        }
        createContext("/auth/v1/verify") { ex ->
            ex.json(mapOf("access_token" to "token", "refresh_token" to "refresh", "expires_in" to 3600, "user" to mapOf("id" to "user-1", "email" to "me@example.com")))
        }
        createContext("/auth/v1/user") { ex ->
            // A token from someone else's account, as a hostile page would use
            if ("attacker" in ex.requestHeaders.getFirst("Authorization").orEmpty()) ex.json(mapOf("id" to "user-9", "email" to "attacker@evil.test"))
            else ex.json(mapOf("id" to "user-1", "email" to "me@example.com"))
        }
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
            // As PostgREST: a start time, or the (time, kind, key) of the row to follow, and a page size
            val query = ex.requestURI.rawQuery.split('&').associate {
                it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            val since = query["updated_at"]?.removePrefix("gte.")
            val after = query["or"]?.let { filter ->
                Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(filter).map { it.groupValues[1].replace(Regex("\\\\(.)"), "$1") }.toList()
            }?.let { Triple(it[0], it[2], it[5]) }
            val limit = query["limit"]?.toIntOrNull() ?: Int.MAX_VALUE
            val list = synchronized(rows) {
                rows.values
                    .filter { since == null || it.updatedAt >= since }
                    .filter { r ->
                        after == null || r.updatedAt > after.first ||
                            (r.updatedAt == after.first && (r.kind > after.second || (r.kind == after.second && r.key > after.third)))
                    }
                    .sortedWith(compareBy<Row>({ it.updatedAt }, { it.kind }, { it.key }))
                    .take(limit)
            }
            afterPage?.invoke()
            ex.json(list.map { mapOf("kind" to it.kind, "item_key" to it.key, "data" to it.data, "deleted" to it.deleted, "client_updated_at" to it.at, "updated_at" to it.updatedAt) })
        }
        start()
    }

    private fun remote(kind: String, key: String, value: Any?, at: Long, deleted: Boolean = false) = synchronized(rows) {
        rows[kind to key] = Row(kind, key, value?.let { jsonMapper.valueToTree<ObjectNode>(it) }, deleted, at, serverTime())
    }

    @Test
    fun aRowSavedElsewhereDuringAPullDoesntHideOthers() = runBlocking {
        val server = server()
        System.setProperty("hyperstream.syncUrl", "http://127.0.0.1:${server.address.port}")
        val client = com.lagradost.cloudstream4.desktop.sync.SupabaseClient
        client.pageSize = 2
        try {
            // Three rows saved together share one time, then one more
            fun row(key: String, time: String) = Row("bookmark", key, null, deleted = false, at = 1, updatedAt = time)
            synchronized(rows) {
                rows.clear()
                listOf("a", "b", "c").forEach { rows["bookmark" to it] = row(it, "2027-01-01T00:00:00Z") }
                rows["bookmark" to "d"] = row("d", "2027-01-01T00:00:01Z")
            }
            // After the first page, another device saves "a" again, which moves it to a later time
            afterPage = {
                afterPage = null
                synchronized(rows) { rows["bookmark" to "a"] = row("a", "2027-01-01T00:00:09Z") }
            }
            val session = com.lagradost.cloudstream4.desktop.sync.SyncSession("token", "refresh", Long.MAX_VALUE, "user-1", null)
            val pulled = client.pull(session, null).map { it.key }
            assertTrue("c" in pulled && "d" in pulled, "no row skipped: $pulled")
            assertEquals(listOf("a", "b", "c", "d", "a"), pulled)
        } finally {
            afterPage = null
            synchronized(rows) { rows.clear() }
            client.pageSize = 500
            server.stop(0)
            System.clearProperty("hyperstream.syncUrl")
        }
    }

    @Test
    fun aRevokedOrUnreachableSignInNeverThrows() = runBlocking {
        val server = server()
        System.setProperty("hyperstream.syncUrl", "http://127.0.0.1:${server.address.port}")
        System.setProperty("cloudstream.dataDir", Files.createTempDirectory("cs-sync-revoked").toString())
        try {
            // A session about to expire, so the next sync refreshes it
            SyncManager.sendLink("me@example.com").join()
            SyncManager.completeRedirect("hyperstream://auth#access_token=abc&refresh_token=old&expires_in=1&type=magiclink")
            assertTrue(SyncManager.ui.value.signedIn)

            // The network is down: sync fails quietly and stays signed in, to try again later
            server.stop(0)
            SyncManager.syncNow()
            assertTrue(SyncManager.ui.value.signedIn, "still signed in while offline")
            assertNotNull(SyncManager.ui.value.error)
        } finally {
            server.stop(0)
        }
        val again = server()
        System.setProperty("hyperstream.syncUrl", "http://127.0.0.1:${again.address.port}")
        try {
            // The server no longer knows the refresh token: signed out, with a message, no crash
            SyncManager.syncNow()
            assertTrue(!SyncManager.ui.value.signedIn, "signed out after the refresh was refused")
            assertTrue(SyncManager.ui.value.error.orEmpty().contains("Sign in again"))
        } finally {
            again.stop(0)
            System.clearProperty("hyperstream.syncUrl")
        }
    }

    @Test
    fun pushesPullsAndKeepsTheNewerChange() = runBlocking {
        // Small pages, so every pull here goes over several
        com.lagradost.cloudstream4.desktop.sync.SupabaseClient.pageSize = 3
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
            assertTrue(synchronized(rows) { rows.containsKey("setting" to "themeMode") }, "settings were pushed, one per setting")
            assertTrue(synchronized(rows) { !rows.containsKey("setting" to "volume") }, "device settings stay on the device")

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

            // The same item read back with its keys in another order (as Postgres stores JSON objects)
            // isn't a change, so nothing is sent back
            val stored = synchronized(rows) { rows["history" to "Prov|ep1"]!! }
            val reordered = jsonMapper.createObjectNode().apply {
                stored.data!!.fieldNames().asSequence().toList().reversed().forEach { set<JsonNode>(it, stored.data.get(it)) }
            }
            synchronized(rows) { rows["history" to "Prov|ep1"] = stored.copy(data = reordered, at = stored.at + 1, updatedAt = serverTime()) }
            SyncManager.syncNow()
            SyncManager.syncNow()
            assertEquals(stored.at + 1, synchronized(rows) { rows["history" to "Prov|ep1"]!! }.at, "an unchanged item wasn't sent again")

            // Two devices change different settings: both changes stay
            com.lagradost.cloudstream4.desktop.data.SettingsStore.update { it.copy(seekSeconds = 30) }
            remote("setting", "autoPlayNext", mapOf("value" to false), at = System.currentTimeMillis())
            SyncManager.syncNow()
            assertEquals(30, com.lagradost.cloudstream4.desktop.data.SettingsStore.state.value.seekSeconds, "this device's change kept")
            assertEquals(false, com.lagradost.cloudstream4.desktop.data.SettingsStore.state.value.autoPlayNext, "the other device's change arrived")
            assertEquals(30, synchronized(rows) { rows["setting" to "seekSeconds"] }!!.data!!["value"].asInt(), "and this one was sent")

            // Another device whose clock runs 2 minutes ahead changes a setting; changing it back here
            // still reaches the server, though this device's clock says it's earlier
            remote("setting", "skipButtons", mapOf("value" to false), at = System.currentTimeMillis() + 120_000)
            SyncManager.syncNow()
            assertEquals(false, com.lagradost.cloudstream4.desktop.data.SettingsStore.state.value.skipButtons, "the other device's change arrived")
            com.lagradost.cloudstream4.desktop.data.SettingsStore.update { it.copy(skipButtons = true) }
            SyncManager.syncNow()
            assertEquals(true, synchronized(rows) { rows["setting" to "skipButtons"] }!!.data!!["value"].asBoolean(), "the change made here wasn't refused as older")

            // A setting changed here while offline, after an older change to it on another device:
            // the change made here is kept, and sent
            Thread.sleep(5)
            remote("setting", "sourceOrder", mapOf("value" to "provider"), at = System.currentTimeMillis())
            Thread.sleep(5)
            com.lagradost.cloudstream4.desktop.data.SettingsStore.update { it.copy(sourceOrder = "size") }
            SyncManager.syncNow()
            assertEquals("size", com.lagradost.cloudstream4.desktop.data.SettingsStore.state.value.sourceOrder, "the newer change made here stayed")
            assertEquals("size", synchronized(rows) { rows["setting" to "sourceOrder"] }!!.data!!["value"].asText(), "and was sent")

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

            // Searches sync both ways, one item per query
            com.lagradost.cloudstream4.desktop.data.SearchHistory.add("Dune")
            SyncManager.syncNow()
            assertTrue(synchronized(rows) { rows["search" to "dune"] }?.deleted == false, "the search was sent")
            remote("search", "interstellar", com.lagradost.cloudstream4.desktop.data.SearchHistoryEntry("Interstellar", at = System.currentTimeMillis()), at = System.currentTimeMillis())
            SyncManager.syncNow()
            assertTrue(com.lagradost.cloudstream4.desktop.data.SearchHistory.entries.value.any { it.query == "Interstellar" }, "the other device's search arrived")
            com.lagradost.cloudstream4.desktop.data.SearchHistory.remove("dune")
            SyncManager.syncNow()
            assertTrue(synchronized(rows) { rows["search" to "dune"] }?.deleted == true, "removing it here removed it there")

            // Repositories and extensions carry no change time of their own. One added here that was
            // never synced from here isn't taken away by an old removal from another device: it stays,
            // and is sent as added
            val repoUrl = "http://127.0.0.1:1/sync-test-repo.json"
            remote("repository", repoUrl, null, at = 1_000, deleted = true)
            ExtensionManager.addRepositoryData(RepositoryData("Sync Test", repoUrl))
            SyncManager.syncNow()
            delay(300) // a removal would run in the background
            assertTrue(ExtensionManager.repositories().any { it.url == repoUrl }, "an old removal didn't remove a repository added here")
            assertTrue(synchronized(rows) { rows["repository" to repoUrl] }?.deleted == false, "and it was sent as added")

            // Removed on another device after this one synced it: removed here too
            val removedAt = System.currentTimeMillis()
            remote("repository", repoUrl, null, at = removedAt, deleted = true)
            SyncManager.syncNow()
            withTimeout(5_000) { ExtensionManager.state.first { s -> s.repositories.none { it.data.url == repoUrl } } }

            // Added back here right after: the same removal, pulled again by the next pass, doesn't undo it
            Thread.sleep(5)
            ExtensionManager.addRepositoryData(RepositoryData("Sync Test", repoUrl))
            SyncManager.syncNow()
            SyncManager.syncNow()
            delay(300)
            assertTrue(ExtensionManager.repositories().any { it.url == repoUrl }, "adding it back stuck")
            assertTrue(synchronized(rows) { rows["repository" to repoUrl] }?.deleted == false, "and reached the server")
            ExtensionManager.removeRepository(repoUrl).join()
            SyncManager.syncNow()
            assertTrue(synchronized(rows) { rows["repository" to repoUrl] }?.deleted == true, "removing it here removed it there")

            // An extension installed on another device that fails to download here: tried a few times,
            // not again on every pass, and not sent back as removed
            val extRepo = "http://127.0.0.1:${server.address.port}/repo/repo.json"
            ExtensionManager.addRepositoryData(RepositoryData("Ext Repo", extRepo))
            withTimeout(10_000) { ExtensionManager.state.first { s -> s.repositories.any { it.data.url == extRepo && it.plugins.isNotEmpty() } } }
            remote("extension", "$extRepo|Broken", mapOf("internalName" to "Broken", "name" to "Broken", "repositoryUrl" to extRepo), at = System.currentTimeMillis())
            repeat(6) {
                SyncManager.syncNow()
                delay(300)
                withTimeout(10_000) { ExtensionManager.state.first { "Broken" !in it.busy } }
            }
            assertTrue(brokenDownloads.get() in 1..3, "tried ${brokenDownloads.get()} times")
            assertTrue(synchronized(rows) { rows["extension" to "$extRepo|Broken"] }?.deleted == false, "not sent back as removed")
            ExtensionManager.removeRepository(extRepo).join()
            SyncManager.syncNow()
            ExtensionManager.dismissMessage()

            // A data file that couldn't be read: its items are missing, not deleted, so no deletions go out
            LibraryStore.setBookmark(Bookmark("kept", "Prov", "Kept", null, null, null, WatchType.WATCHING), "Prov", "kept")
            SyncManager.syncNow()
            com.lagradost.cloudstream4.desktop.data.DataHealth.unreadable += "bookmarks.json"
            LibraryStore.setBookmark(null, "Prov", "kept")
            SyncManager.syncNow()
            assertTrue(synchronized(rows) { rows["bookmark" to "Prov|kept"] }?.deleted == false, "nothing deleted while a file is unreadable")
            assertNotNull(LibraryStore.state.value.bookmark("Prov", "kept"), "and the server's copy came back")
            com.lagradost.cloudstream4.desktop.data.DataHealth.unreadable.clear()

            // A damaged file on one run, then a later run that reads it fine but holds only what
            // was saved since: what it lost comes back from the server, and nothing is deleted
            val health = com.lagradost.cloudstream4.desktop.data.DataHealth.unreadable
            LibraryStore.setBookmark(Bookmark("old", "Prov", "Old", null, null, null, WatchType.WATCHING), "Prov", "old")
            SyncManager.syncNow()
            // Much later, so incremental pulls no longer return it
            clock += 600_000
            remote("search", "later", com.lagradost.cloudstream4.desktop.data.SearchHistoryEntry("Later", at = System.currentTimeMillis()), at = System.currentTimeMillis())
            SyncManager.syncNow()
            health += "bookmarks.json"
            LibraryStore.setBookmark(null, "Prov", "old")
            SyncManager.syncNow()
            health.clear()
            SyncManager.syncNow()
            assertTrue(synchronized(rows) { rows["bookmark" to "Prov|old"] }?.deleted == false, "the lost bookmark wasn't deleted on the server")
            assertNotNull(LibraryStore.state.value.bookmark("Prov", "old"), "and came back here")

            // A damaged settings file: the defaults it falls back to don't replace the synced settings
            health += "settings.json"
            com.lagradost.cloudstream4.desktop.data.SettingsStore.update { com.lagradost.cloudstream4.desktop.data.AppSettings() }
            SyncManager.syncNow()
            health.clear()
            SyncManager.syncNow()
            assertEquals(30, com.lagradost.cloudstream4.desktop.data.SettingsStore.state.value.seekSeconds, "the synced setting came back")
            assertEquals(30, synchronized(rows) { rows["setting" to "seekSeconds"] }!!.data!!["value"].asInt(), "and the server kept it")

            // Settings from a newer version, and an older version removing them: no endless back and forth
            remote("setting", "fromANewerVersion", mapOf("value" to 1), at = System.currentTimeMillis())
            SyncManager.syncNow()
            SyncManager.syncNow()
            assertTrue(synchronized(rows) { rows["setting" to "fromANewerVersion"] }?.deleted == false, "an unknown setting isn't removed")
            remote("setting", "seekSeconds", null, at = System.currentTimeMillis(), deleted = true)
            SyncManager.syncNow()
            assertEquals(30, com.lagradost.cloudstream4.desktop.data.SettingsStore.state.value.seekSeconds, "a removed setting stays as it is")

            // All settings in one item, from a version before 1.2.1: older than a change made here, it doesn't undo it
            remote("settings", "app", mapOf("seekSeconds" to 5, "autoPlayNext" to true), at = 1_000)
            SyncManager.syncNow()
            assertEquals(30, com.lagradost.cloudstream4.desktop.data.SettingsStore.state.value.seekSeconds, "the old item didn't undo a newer change")
            SyncManager.syncNow()
            assertTrue(synchronized(rows) { rows["settings" to "app"] }?.deleted == false, "and isn't removed, so the old device doesn't send it again")

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
            SyncManager.sendLink("me@example.com").join()
            SyncManager.completeRedirect("hyperstream://auth#access_token=abc&refresh_token=def&expires_in=3600&type=magiclink")
            assertTrue(SyncManager.ui.value.signedIn && !SyncManager.ui.value.choosingPassword)
            SyncManager.signOut().join()

            // A link this device didn't ask for, or for another account, doesn't sign it in
            assertTrue(runCatching { SyncManager.completeRedirect("hyperstream://auth#access_token=abc&refresh_token=def&expires_in=3600&type=magiclink") }.isFailure)
            assertTrue(!SyncManager.ui.value.signedIn, "no sign-in was asked for")
            SyncManager.sendLink("me@example.com").join()
            assertTrue(runCatching { SyncManager.completeRedirect("hyperstream://auth#access_token=attacker&refresh_token=x&expires_in=3600&type=magiclink") }.isFailure)
            assertTrue(!SyncManager.ui.value.signedIn, "a link for someone else's account is refused")

            // Opening the emailed link on the Mac: it returns to the app's local page, which hands over the session
            SyncManager.sendLink("me@example.com").join()
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
            com.lagradost.cloudstream4.desktop.sync.SupabaseClient.pageSize = 500
        }
    }
}
