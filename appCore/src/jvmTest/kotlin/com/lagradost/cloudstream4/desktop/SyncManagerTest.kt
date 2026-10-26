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
    private val pushes = java.util.concurrent.atomic.AtomicInteger()
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
            pushes.incrementAndGet()
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

            // A data file that couldn't be read: its items are missing, not deleted, so no deletions go out
            LibraryStore.setBookmark(Bookmark("kept", "Prov", "Kept", null, null, null, WatchType.WATCHING), "Prov", "kept")
            SyncManager.syncNow()
            com.lagradost.cloudstream4.desktop.data.DataHealth.unreadable += "bookmarks.json"
            LibraryStore.setBookmark(null, "Prov", "kept")
            SyncManager.syncNow()
            assertTrue(synchronized(rows) { rows["bookmark" to "Prov|kept"] }?.deleted == false, "nothing deleted while a file is unreadable")
            assertNotNull(LibraryStore.state.value.bookmark("Prov", "kept"), "and the server's copy came back")
            com.lagradost.cloudstream4.desktop.data.DataHealth.unreadable.clear()

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
        }
    }

    /** Signed out, then in again as me@example.com, with nothing known from an earlier test. */
    private suspend fun signInFresh() {
        SyncManager.signOut().join()
        SyncManager.sendLink("me@example.com").join()
        SyncManager.verify("123456").join()
        withTimeout(5_000) { SyncManager.ui.first { it.signedIn } }
        // An earlier test may have left this device on another account
        if (SyncManager.ui.value.askMerge) SyncManager.chooseMerge(keepThisDevice = true).join()
    }

    private fun withSyncServer(block: suspend () -> Unit) = runBlocking {
        val server = server()
        System.setProperty("hyperstream.syncUrl", "http://127.0.0.1:${server.address.port}")
        try {
            signInFresh()
            block()
        } finally {
            SyncManager.signOut().join()
            server.stop(0)
            System.clearProperty("hyperstream.syncUrl")
        }
    }

    private fun row(kind: String, key: String) = synchronized(rows) { rows[kind to key] }

    @Test
    fun repositoriesFromAnotherDeviceWaitForTheUser() = withSyncServer {
        val ExtensionManager = com.lagradost.cloudstream4.desktop.plugins.ExtensionManager
        val declined = com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData(null, "Someone else's", "http://127.0.0.1:9/declined.json")
        val wanted = com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData(null, "My other one", "http://127.0.0.1:9/wanted.json")
        remote("repository", declined.url, declined, at = 1)
        remote("extension", "${declined.url}|Evil", mapOf("internalName" to "Evil", "name" to "Evil", "repositoryUrl" to declined.url), at = 1)
        SyncManager.syncNow()
        assertTrue(ExtensionManager.repositories().none { it.url == declined.url }, "a repository isn't added just because the account lists it")
        assertEquals(listOf(declined.url), SyncManager.ui.value.suggestedRepositories.map { it.url }, "it's offered instead")

        SyncManager.declineSuggestedRepositories()
        SyncManager.syncNow()
        assertTrue(SyncManager.ui.value.suggestedRepositories.isEmpty(), "declined ones aren't offered again")
        assertTrue(ExtensionManager.repositories().none { it.url == declined.url })
        assertEquals(false, row("repository", declined.url)?.deleted, "declining here doesn't remove it from the other devices")

        remote("repository", wanted.url, wanted, at = 2)
        SyncManager.syncNow()
        assertEquals(listOf(wanted.url), SyncManager.ui.value.suggestedRepositories.map { it.url })
        SyncManager.addSuggestedRepositories().join()
        assertTrue(ExtensionManager.repositories().any { it.url == wanted.url }, "added once the user agreed")
        ExtensionManager.removeRepository(wanted.url).join()
    }

    @Test
    fun settingsFromANewerVersionAreLeftAlone() = withSyncServer {
        val theme = com.lagradost.cloudstream4.desktop.data.SettingsStore.state.value.themeMode
        val now = System.currentTimeMillis()
        // A setting this version doesn't have, and a value it can't hold
        remote("setting", "settingFromTheFuture", mapOf("value" to true), at = now)
        remote("setting", "themeMode", mapOf("value" to "Sparkly"), at = now)
        SyncManager.syncNow()
        SyncManager.syncNow()
        assertEquals(theme, com.lagradost.cloudstream4.desktop.data.SettingsStore.state.value.themeMode, "kept this device's value")
        assertEquals("Sparkly", row("setting", "themeMode")!!.data!!["value"].asText(), "and didn't send it back over the newer device's")
        assertEquals(false, row("setting", "settingFromTheFuture")!!.deleted, "an unknown setting isn't deleted for everyone")
    }

    @Test
    fun longKeysAreSentAsTheirHash() = withSyncServer {
        val data = "{\"url\":\"" + "x".repeat(3000) + "\"}"
        val entry = HistoryEntry("long-show", "Prov", "Long", null, null, data, null, null, null, 10_000, 100_000, updatedAt = 1_000)
        LibraryStore.saveProgress(entry)
        SyncManager.syncNow()
        val sent = synchronized(rows) { rows.values.single { it.kind == "history" && it.data?.get("episodeData")?.asText() == data } }
        assertTrue(sent.key.startsWith("sha256:") && sent.key.length < 100, "sent under a short key: ${sent.key.take(40)}")

        remote("history", sent.key, entry.copy(positionMs = 50_000, updatedAt = 2_000), at = 2_000)
        SyncManager.syncNow()
        assertEquals(50_000, LibraryStore.state.value.progress("Prov", data)?.positionMs, "another device's change to it arrives")
        assertEquals(1, LibraryStore.state.value.history.count { it.episodeData == data }, "as the same episode, not a copy")
    }

    @Test
    fun removingFromContinueWatchingReachesOtherDevices() = withSyncServer {
        val entry = HistoryEntry("cw-show", "Prov", "Continue", null, null, "cw-ep", null, 1, 1, 60_000, 1_200_000, updatedAt = 1_000)
        LibraryStore.saveProgress(entry)
        SyncManager.syncNow()
        LibraryStore.removeFromContinueWatching("Prov", "cw-show")
        SyncManager.syncNow()
        assertEquals(1_200_000, row("history", "Prov|cw-ep")!!.data!!["positionMs"].asLong(), "the server took the removal")
    }

    @Test
    fun signingInToAnotherAccountAsksFirst() = withSyncServer {
        LibraryStore.saveProgress(HistoryEntry("mine", "Prov", "Mine", null, null, "mine-ep", null, null, null, 5_000, 100_000, updatedAt = 1_000))
        SyncManager.syncNow()
        SyncManager.signOut().join()

        SyncManager.signInWithPassword("new@example.com", "secret1", create = true).join()
        withTimeout(5_000) { SyncManager.ui.first { it.signedIn } }
        assertTrue(SyncManager.ui.value.askMerge, "asks what to do with this device's library")
        val before = pushes.get()
        SyncManager.syncNow()
        assertEquals(before, pushes.get(), "and sends nothing until answered")

        SyncManager.chooseMerge(keepThisDevice = false).join()
        assertTrue(!SyncManager.ui.value.askMerge)
        assertTrue(LibraryStore.state.value.history.none { it.parentUrl == "mine" }, "replaced by the new account's library")

        // Back on the usual account for the other tests
        signInFresh()
    }
}
