package com.lagradost.cloudstream4.desktop.sync

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.cloudstream4.desktop.data.AppSettings
import com.lagradost.cloudstream4.desktop.data.DataHealth
import com.lagradost.cloudstream4.desktop.data.Bookmark
import com.lagradost.cloudstream4.desktop.data.HistoryEntry
import com.lagradost.cloudstream4.desktop.data.LibraryStore
import com.lagradost.cloudstream4.desktop.data.RememberedSource
import com.lagradost.cloudstream4.desktop.data.SearchHistory
import com.lagradost.cloudstream4.desktop.data.SearchHistoryEntry
import com.lagradost.cloudstream4.desktop.data.SettingsStore
import com.lagradost.cloudstream4.desktop.data.SourceMemory
import com.lagradost.cloudstream4.desktop.data.jsonFile
import com.lagradost.cloudstream4.desktop.data.jsonMapper
import com.lagradost.cloudstream4.desktop.plugins.ExtensionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** What the device last agreed with the server on, for one item: its content hash and change time. */
data class KnownItem(@JsonProperty("hash") val hash: Int, @JsonProperty("at") val at: Long)

/** A change from another device that can't be applied yet (an extension whose repository is still loading). */
data class PendingItem(
    @JsonProperty("kind") val kind: String,
    @JsonProperty("key") val key: String,
    @JsonProperty("data") val data: JsonNode?,
    @JsonProperty("at") val at: Long,
)

/** Saved sync state: the session, how far this device has pulled, and what it has in common with the server. */
data class SyncStore(
    @JsonProperty("session") val session: SyncSession? = null,
    @JsonProperty("cursor") val cursor: String? = null,
    @JsonProperty("known") val known: Map<String, KnownItem> = emptyMap(),
    @JsonProperty("pending") val pending: Map<String, PendingItem> = emptyMap(),
    @JsonProperty("lastSyncedAt") val lastSyncedAt: Long? = null,
    /**
     * The sign-in or reset email this device asked for, and when. Only a link back for that address,
     * soon after, signs it in, so a web page can't sign the app in to another account.
     */
    @JsonProperty("pendingEmail") val pendingEmail: String? = null,
    @JsonProperty("pendingAt") val pendingAt: Long? = null,
)

data class SyncUiState(
    val email: String? = null,
    /** Where the sign-in email went, while waiting for its code. */
    val codeSentTo: String? = null,
    val busy: Boolean = false,
    val syncing: Boolean = false,
    /** A code for signing a TV in, shown on a signed-in phone or Mac, and when it stops working. */
    val tvCode: String? = null,
    val tvCodeExpiresAt: Long? = null,
    val lastSyncedAt: Long? = null,
    val error: String? = null,
    /** A short confirmation, such as a saved password. */
    val notice: String? = null,
    /** Where a password reset email went. */
    val resetSentTo: String? = null,
    /** Signed in to reset the password: ask for the new one. */
    val choosingPassword: Boolean = false,
) {
    val signedIn: Boolean get() = email != null
}

/**
 * Optional sync of watch progress, library, settings, the source each title last played from,
 * repositories and installed extensions, through Supabase. The app keeps working from its own
 * files; when signed in, each pass pulls what changed on the server since last time (applying it
 * where it's newer), then pushes this device's own changes. Deletions sync too.
 */
object SyncManager {
    private const val HISTORY = "history"
    private const val BOOKMARK = "bookmark"
    /** Before 1.2.1 all settings were one item; still applied when an older device sends it. */
    private const val SETTINGS = "settings"
    /** One setting per item, so changes to different settings on two devices both survive. */
    private const val SETTING = "setting"
    private const val SOURCE = "source"
    private const val REPOSITORY = "repository"
    private const val EXTENSION = "extension"
    private const val SEARCH = "search"

    /** Settings that belong to one device: folders, connection counts, volume. Everything else syncs. */
    /** Settings that belong to one device's screen, input or storage, so they don't sync. */
    private val deviceSettings = setOf(
        "downloadFolder", "downloadConnections", "streamConnections", "parallelDownloads", "volume", "demoProvider",
        "videoFit", "swipeControls",
    )

    // Sync is optional: whatever goes wrong in it (no network, a revoked sign-in) must never close the app
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t -> System.err.println("Sync: $t") },
    )
    private val refreshLock = Mutex()
    private val file = jsonFile<SyncStore>("sync.json") { SyncStore() }
    private var store = file.read()
    private val lock = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null

    private val _ui = MutableStateFlow(SyncUiState(email = store.session?.email, lastSyncedAt = store.lastSyncedAt))
    val ui: StateFlow<SyncUiState> = _ui.asStateFlow()

    private fun save(update: (SyncStore) -> SyncStore) {
        store = update(store)
        file.write(store)
    }

    /** Starts syncing in the background if signed in. Call once at app start. */
    @OptIn(FlowPreview::class)
    fun start() {
        if (loop != null) return
        loop = scope.launch {
            // Any local change syncs a few seconds later; otherwise every minute
            launch {
                combine(LibraryStore.state, SettingsStore.state, SourceMemory.changes, ExtensionManager.state, SearchHistory.entries) { _, _, _, _, _ -> Unit }
                    .drop(1)
                    .debounce(4_000)
                    .collect { wake.trySend(Unit) }
            }
            while (true) {
                if (store.session != null) syncNow()
                withTimeoutOrNull(60_000) { wake.receive() }
            }
        }
    }

    fun requestSync() {
        wake.trySend(Unit)
    }

    // ---------- Sign in ----------

    /** Emails a sign-in link; opening it on this device signs the app in. */
    fun sendLink(email: String) = scope.launch {
        val address = email.trim()
        if (!address.contains('@')) {
            _ui.update { it.copy(error = "Enter your email address.") }
            return@launch
        }
        _ui.update { it.copy(busy = true, error = null) }
        try {
            SignInRedirect.prepare()
            SupabaseClient.sendLink(address, SignInRedirect.url)
            save { it.copy(pendingEmail = address, pendingAt = System.currentTimeMillis()) }
            _ui.update { it.copy(busy = false, codeSentTo = address) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            _ui.update { it.copy(busy = false, error = "Couldn't send the email: ${emailError(t)}") }
        }
    }

    /** Emails a link that signs this device in and asks for a new password. */
    fun sendPasswordReset(email: String) = scope.launch {
        val address = email.trim()
        if (!address.contains('@')) {
            _ui.update { it.copy(error = "Enter your email address.") }
            return@launch
        }
        _ui.update { it.copy(busy = true, error = null) }
        try {
            SignInRedirect.prepare()
            SupabaseClient.sendPasswordReset(address, SignInRedirect.url)
            save { it.copy(pendingEmail = address, pendingAt = System.currentTimeMillis()) }
            _ui.update { it.copy(busy = false, resetSentTo = address) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            _ui.update { it.copy(busy = false, error = "Couldn't send the email: ${emailError(t)}") }
        }
    }

    fun cancelReset() {
        save { it.copy(pendingEmail = null, pendingAt = null) }
        _ui.update { it.copy(resetSentTo = null, error = null) }
    }

    private fun emailError(t: Throwable): String {
        val message = t.message.orEmpty()
        return if ("rate limit" in message || "not authorized" in message) {
            "$message. The sync server can only send a few emails an hour, and only to its owner. Use a code from a signed-in device instead."
        } else message
    }

    /** Signs in with the 6-digit code from the email, or the sign-in link copied from it. */
    fun verify(codeOrLink: String) = scope.launch {
        val input = codeOrLink.trim()
        val email = _ui.value.codeSentTo
        _ui.update { it.copy(busy = true, error = null) }
        try {
            val session = if (input.contains("://") || "access_token=" in input) SupabaseClient.verifyLink(input)
            else SupabaseClient.verifyCode(email ?: error("Ask for a sign-in email first."), input.filter { it.isDigit() })
            requireRequested(session)
            signedIn(session, email ?: _ui.value.resetSentTo, choosingPassword = isRecovery(input))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            _ui.update { it.copy(busy = false, error = "Sign-in didn't work: ${t.message}") }
        }
    }

    /** A password reset link, rather than a sign-in link. */
    private fun isRecovery(link: String) = Regex("[?#&]type=recovery").containsMatchIn(link)

    /** Where a sign-in link returned to this device (the Mac's page or hyperstream://auth on Android). */
    suspend fun completeRedirect(link: String) {
        try {
            val session = SupabaseClient.sessionFromRedirect(link)
            requireRequested(session)
            signedIn(session, _ui.value.codeSentTo ?: _ui.value.resetSentTo, choosingPassword = isRecovery(link))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            _ui.update { it.copy(busy = false, error = "Sign-in didn't work: ${t.message}") }
            throw t
        }
    }

    fun completeRedirectLater(link: String) = scope.launch { runCatching { completeRedirect(link) } }

    /**
     * A link signs in only if this device asked for an email to that address in the last two hours
     * (a link lasts one). Any page can open hyperstream://auth or the Mac's local sign-in page with a
     * session for an account of its own; without this check the app would switch to that account,
     * send it this device's library and install the extensions it lists.
     */
    private fun requireRequested(session: SyncSession) {
        val pending = store.pendingEmail?.takeIf { (store.pendingAt ?: 0) > System.currentTimeMillis() - 2 * 60 * 60_000 }
        if (pending == null || !pending.equals(session.email, ignoreCase = true)) {
            throw java.io.IOException("This link isn't for the sign-in asked for on this device. Ask for a new email here, then open its link.")
        }
    }

    /**
     * Signs a TV (or any device) in with the code from a signed-in phone or Mac. With [resetPassword],
     * for a forgotten password, it then asks for a new one.
     */
    fun signInWithTvCode(code: String, resetPassword: Boolean = false) = scope.launch {
        val digits = code.filter { it.isDigit() }
        if (digits.length != 8) {
            _ui.update { it.copy(error = "Enter the 8-digit code from your phone or Mac.") }
            return@launch
        }
        _ui.update { it.copy(busy = true, error = null) }
        try {
            signedIn(SupabaseClient.signInWithTvCode(digits), null, choosingPassword = resetPassword)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            _ui.update { it.copy(busy = false, error = t.message ?: "That code didn't work.") }
        }
    }

    /** Signs in with email and password, or creates the account when [create]. */
    fun signInWithPassword(email: String, password: String, create: Boolean) = scope.launch {
        val address = email.trim()
        when {
            !address.contains('@') -> { _ui.update { it.copy(error = "Enter your email address.") }; return@launch }
            password.length < 6 -> { _ui.update { it.copy(error = "Use a password of at least 6 characters.") }; return@launch }
        }
        _ui.update { it.copy(busy = true, error = null) }
        try {
            val session = if (create) SupabaseClient.signUp(address, password) else SupabaseClient.signInWithPassword(address, password)
            signedIn(session, address)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            val message = t.message.orEmpty()
            _ui.update {
                it.copy(
                    busy = false,
                    error = when {
                        "Invalid login" in message -> "Wrong email or password. Accounts made with an email link have no password until you set one."
                        "already registered" in message -> "There's already an account for this email. Sign in instead."
                        else -> (if (create) "Couldn't create the account: " else "Couldn't sign in: ") + message
                    },
                )
            }
        }
    }

    /** Gives a signed-in account a password, for signing in on devices without email. */
    fun setPassword(password: String) = scope.launch {
        if (password.length < 6) {
            _ui.update { it.copy(error = "Use a password of at least 6 characters.") }
            return@launch
        }
        val session = sessionOrError() ?: return@launch
        _ui.update { it.copy(busy = true, error = null) }
        try {
            SupabaseClient.setPassword(session, password)
            _ui.update { it.copy(busy = false, choosingPassword = false, notice = "Password saved. You can sign in with it on your other devices.") }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            _ui.update { it.copy(busy = false, error = "Couldn't save the password: ${t.message}") }
        }
    }

    /** On a signed-in phone or Mac: a code to type on a TV. */
    fun showTvCode() = scope.launch {
        val session = sessionOrError() ?: return@launch
        _ui.update { it.copy(busy = true, error = null) }
        try {
            val code = SupabaseClient.createTvCode(session)
            _ui.update { it.copy(busy = false, tvCode = code, tvCodeExpiresAt = System.currentTimeMillis() + 10 * 60_000) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            _ui.update { it.copy(busy = false, error = "Couldn't make a TV code: ${t.message}") }
        }
    }

    fun hideTvCode() = _ui.update { it.copy(tvCode = null, tvCodeExpiresAt = null) }

    /** Keeps the current password after signing in to reset it. */
    fun skipNewPassword() = _ui.update { it.copy(choosingPassword = false) }

    private fun signedIn(session: SyncSession, email: String?, choosingPassword: Boolean = false) {
        // A new account starts from this device's data; known items from another account don't apply
        val previous = store.session?.userId
        save { if (previous == session.userId) it.copy(session = session) else SyncStore(session = session.copy(email = session.email ?: email)) }
        _ui.update { SyncUiState(email = session.email ?: email, choosingPassword = choosingPassword) }
        requestSync()
    }

    fun cancelCode() {
        save { it.copy(pendingEmail = null, pendingAt = null) }
        _ui.update { it.copy(codeSentTo = null, error = null) }
    }

    /** Stops syncing on this device. Its data stays as it is. */
    fun signOut() = scope.launch {
        val session = store.session
        lock.withLock { save { SyncStore() } }
        _ui.value = SyncUiState()
        if (session != null) SupabaseClient.signOut(session)
    }

    /** The session for something the user asked for, or null with the reason shown. */
    private suspend fun sessionOrError(): SyncSession? = try {
        session()
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        _ui.update { it.copy(busy = false, error = "Couldn't reach the sync server: ${t.message ?: t.javaClass.simpleName}") }
        null
    }

    private suspend fun session(): SyncSession? {
        val current = store.session ?: return null
        if (current.expiresAt - 120_000 > System.currentTimeMillis()) return current
        return refreshed(current)
    }

    // One refresh at a time: each refresh token works once, so a second refresh with the same one
    // would fail (and the server may then end the session)
    private suspend fun refreshed(current: SyncSession): SyncSession? = refreshLock.withLock {
        val saved = store.session ?: return@withLock null
        if (saved.refreshToken != current.refreshToken) return@withLock saved
        try {
            val fresh = SupabaseClient.refresh(current)
            // Signed out (or into another account) while the refresh was out: don't bring it back
            if (store.session?.refreshToken != current.refreshToken) return@withLock store.session
            fresh.also { save { it.copy(session = fresh) } }
        } catch (e: SyncAuthException) {
        // The session was revoked or expired for good: sign in again
        save { SyncStore() }
            _ui.value = SyncUiState(error = "Signed out of sync. Sign in again to keep syncing.")
            null
        }
    }

    private fun overlap(cursor: String): String =
        runCatching { java.time.OffsetDateTime.parse(cursor).minusSeconds(10).toString() }.getOrDefault(cursor)

    // ---------- Syncing ----------

    suspend fun syncNow() = lock.withLock {
        if (store.session == null) return@withLock
        _ui.update { it.copy(syncing = true) }
        try {
            // Inside the try: refreshing needs the network, which may be down
            val first = session() ?: return@withLock _ui.update { it.copy(syncing = false) }
            try {
                pass(first)
            } catch (e: SyncAuthException) {
                // An access token can stop working early; refresh once and try again
                refreshed(first)?.let { pass(it) }
            }
            save { it.copy(lastSyncedAt = System.currentTimeMillis()) }
            _ui.update { it.copy(syncing = false, lastSyncedAt = store.lastSyncedAt, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            _ui.update { it.copy(syncing = false, error = "Sync failed: ${t.message ?: t.javaClass.simpleName}") }
        }
    }

    private suspend fun pass(session: SyncSession) {
        // Pull first, so a new device adopts what's already there before sending its own
        // A few seconds back from where the last pass ended: a save that started earlier but
        // finished later carries an earlier time, and would otherwise never be pulled. Rows seen
        // twice change nothing
        val remote = SupabaseClient.pull(session, store.cursor?.let(::overlap))
        val local = localItems()
        val known = store.known.toMutableMap()
        val pending = store.pending.toMutableMap()
        // Removals from elsewhere that finish in the background (repositories, extensions): not
        // to be mistaken for local items in this pass's push
        val removedNow = mutableSetOf<String>()
        for (item in remote) {
            val id = "${item.kind}/${item.key}"
            val mine = local[id]
            if (item.deleted) {
                pending.remove(id)
                val myAt = mine?.at ?: known[id]?.at ?: 0
                if (mine != null && item.at >= myAt) {
                    removeLocal(item.kind, item.key)
                    removedNow += id
                }
                if (mine == null || item.at >= myAt) known.remove(id)
                continue
            }
            val value = decode(item.kind, item.data) ?: continue
            val hash = hashOf(value)
            if (mine != null && hashOf(mine.data) == hash) {
                known[id] = KnownItem(hash, maxOf(item.at, known[id]?.at ?: 0))
                continue
            }
            // Removed here but not sent yet: only a later change elsewhere brings it back. Unless a data
            // file couldn't be read: then it's missing, not removed, and the server's copy restores it
            val removedHere = mine == null && known[id] != null && DataHealth.unreadable.isEmpty()
            val myAt = mine?.at ?: known[id]?.at ?: 0
            if ((mine == null && !removedHere) || item.at > myAt) {
                if (applyLocal(item.kind, item.key, value)) known[id] = KnownItem(hash, item.at)
                else pending[id] = PendingItem(item.kind, item.key, item.data, item.at)
            }
        }
        val cursor = remote.maxOfOrNull { it.serverTime } ?: store.cursor
        save { it.copy(cursor = cursor, known = known, pending = pending) }

        // Extensions waiting for their repository to load
        retryPending()

        // Push what changed here since the last pass
        val now = System.currentTimeMillis()
        val current = localItems()
        val changes = mutableListOf<LocalChange>()
        val next = store.known.toMutableMap()
        for ((id, item) in current) {
            if (id in store.pending || id in removedNow) continue
            val hash = hashOf(item.data)
            val was = next[id]
            if (was == null || was.hash != hash) {
                val at = item.at ?: now
                changes += LocalChange(item.kind, item.key, item.data, deleted = false, at = at)
                next[id] = KnownItem(hash, at)
            }
        }
        // Gone since the last pass: a deletion. Not while a data file is unreadable, or its whole
        // contents would be deleted on every device
        val deletions = if (DataHealth.unreadable.isEmpty()) next.keys - current.keys - store.pending.keys else emptySet()
        for (id in deletions) {
            val (kind, key) = id.split('/', limit = 2)
            changes += LocalChange(kind, key, null, deleted = true, at = now)
            next.remove(id)
        }
        if (changes.isNotEmpty()) {
            SupabaseClient.push(session, changes)
            save { it.copy(known = next) }
        }
    }

    private data class LocalItem(val kind: String, val key: String, val data: Any, val at: Long?)

    /** Everything that syncs, as it is on this device now, keyed "kind/key". */
    private fun localItems(): Map<String, LocalItem> {
        val items = mutableListOf<LocalItem>()
        val library = LibraryStore.state.value
        // The snapshot picture is a file on this device, so it stays here
        library.history.forEach { items += LocalItem(HISTORY, LibraryStore.historyKey(it), it.copy(snapshotPath = null), it.updatedAt) }
        library.bookmarks.forEach { items += LocalItem(BOOKMARK, LibraryStore.bookmarkKey(it), it, it.updatedAt) }
        SourceMemory.all().forEach { (key, source) -> items += LocalItem(SOURCE, key, source, source.savedAt) }
        // No change time is kept per setting: one that differs from what was last synced is sent as new
        syncedSettings(SettingsStore.state.value).forEach { (name, value) -> items += LocalItem(SETTING, name, mapOf("value" to value), null) }
        SearchHistory.entries.value.forEach { items += LocalItem(SEARCH, SearchHistory.key(it.query), it, it.at) }
        ExtensionManager.repositories().forEach { items += LocalItem(REPOSITORY, it.url, it, null) }
        ExtensionManager.state.value.installed.forEach { plugin ->
            val repo = plugin.repositoryUrl ?: return@forEach
            items += LocalItem(EXTENSION, "$repo|${plugin.internalName}", mapOf("internalName" to plugin.internalName, "name" to plugin.name, "repositoryUrl" to repo), null)
        }
        return items.associateBy { "${it.kind}/${it.key}" }
    }

    private fun syncedSettings(settings: AppSettings): Map<String, Any?> =
        jsonMapper.convertValue(settings, object : TypeReference<Map<String, Any?>>() {}).filterKeys { it !in deviceSettings }

    /**
     * Keys in a fixed order: the server stores JSON objects with their keys reordered, and an
     * unchanged item read back must hash the same, or devices would keep re-sending it.
     */
    private val canonical = jsonMapper.copy().configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true)

    private fun hashOf(value: Any): Int = canonical.writeValueAsString(value).hashCode()

    private fun decode(kind: String, data: JsonNode?): Any? = runCatching {
        data ?: return null
        when (kind) {
            HISTORY -> jsonMapper.treeToValue(data, HistoryEntry::class.java)
            BOOKMARK -> jsonMapper.treeToValue(data, Bookmark::class.java)
            SOURCE -> jsonMapper.treeToValue(data, RememberedSource::class.java)
            SETTINGS -> jsonMapper.convertValue(data, object : TypeReference<Map<String, Any?>>() {}).filterKeys { it !in deviceSettings }
            SETTING -> jsonMapper.convertValue(data, object : TypeReference<Map<String, Any?>>() {})
            REPOSITORY -> jsonMapper.treeToValue(data, RepositoryData::class.java)
            EXTENSION -> jsonMapper.convertValue(data, object : TypeReference<Map<String, Any?>>() {})
            SEARCH -> jsonMapper.treeToValue(data, SearchHistoryEntry::class.java)
            else -> null
        }
    }.getOrNull()

    /** Applies another device's version here. False when it can't be applied yet. */
    @Suppress("UNCHECKED_CAST")
    private suspend fun applyLocal(kind: String, key: String, value: Any): Boolean = when (kind) {
        HISTORY -> { LibraryStore.applyRemoteHistory(key, value as HistoryEntry); true }
        BOOKMARK -> { LibraryStore.applyRemoteBookmark(key, value as Bookmark); true }
        SOURCE -> { SourceMemory.applyRemote(key, value as RememberedSource); true }
        SETTINGS -> { mergeSettings(value as Map<String, Any?>); true }
        SETTING -> {
            if (key !in deviceSettings) mergeSettings(mapOf(key to (value as Map<String, Any?>)["value"]))
            true
        }
        REPOSITORY -> {
            val data = value as RepositoryData
            ExtensionManager.repositories().any { it.url == data.url } || ExtensionManager.addRepositoryData(data)
        }
        EXTENSION -> installExtension(value as Map<String, Any?>)
        SEARCH -> { SearchHistory.applyRemote(key, value as SearchHistoryEntry); true }
        else -> true
    }

    /** Puts [remote] settings over this device's, leaving the rest as they are. */
    private fun mergeSettings(remote: Map<String, Any?>) {
        SettingsStore.update { current ->
            val merged = jsonMapper.convertValue(current, object : TypeReference<MutableMap<String, Any?>>() {})
            merged.putAll(remote)
            runCatching { jsonMapper.convertValue(merged, AppSettings::class.java) }.getOrDefault(current)
        }
    }

    /** Installs an extension another device has, once its repository's list is loaded. */
    private fun installExtension(value: Map<String, Any?>): Boolean {
        val repo = value["repositoryUrl"] as? String ?: return true
        val name = value["internalName"] as? String ?: return true
        val state = ExtensionManager.state.value
        if (state.installed.any { it.repositoryUrl == repo && it.internalName == name }) return true
        val plugin = state.repositories.firstOrNull { it.data.url == repo }?.plugins?.firstOrNull { it.internalName == name } ?: return false
        // Installing runs in the background; it counts as applied once it's installed, on a later pass
        if (plugin.internalName !in state.busy) ExtensionManager.install(repo, plugin)
        return false
    }

    private suspend fun retryPending() {
        if (store.pending.isEmpty()) return
        val pending = store.pending.toMutableMap()
        val known = store.known.toMutableMap()
        for ((id, item) in store.pending) {
            val value = decode(item.kind, item.data)
            if (value == null) {
                pending.remove(id)
                continue
            }
            if (applyLocal(item.kind, item.key, value)) {
                pending.remove(id)
                known[id] = KnownItem(hashOf(value), item.at)
            }
        }
        save { it.copy(pending = pending, known = known) }
    }

    private fun removeLocal(kind: String, key: String) {
        when (kind) {
            HISTORY -> LibraryStore.applyRemoteHistory(key, null)
            BOOKMARK -> LibraryStore.applyRemoteBookmark(key, null)
            SOURCE -> SourceMemory.applyRemote(key, null)
            REPOSITORY -> ExtensionManager.removeRepository(key)
            SEARCH -> SearchHistory.applyRemote(key, null)
            EXTENSION -> {
                val (repo, name) = key.split('|', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                ExtensionManager.state.value.installed.firstOrNull { it.repositoryUrl == repo && it.internalName == name }?.let { ExtensionManager.uninstall(it) }
            }
        }
    }
}
