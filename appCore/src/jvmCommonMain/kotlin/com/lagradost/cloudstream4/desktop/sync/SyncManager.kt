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
    /** The account this device last synced with, kept after signing out, to notice a switch to another one. */
    @JsonProperty("lastUserId") val lastUserId: String? = null,
    /** Signed in to a different account than before: nothing syncs until the user says what to do with this device's library. */
    @JsonProperty("awaitingChoice") val awaitingChoice: Boolean = false,
    /** Repositories from other devices the user chose not to add here. */
    @JsonProperty("declinedRepositories") val declinedRepositories: Set<String> = emptySet(),
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
    /** Signed in to another account than before: ask whether to keep this device's library in it. */
    val askMerge: Boolean = false,
    /**
     * Repositories the account's other devices use that aren't added here. They run code, so they're
     * only added when the user says so, never just because the account lists them.
     */
    val suggestedRepositories: List<RepositoryData> = emptyList(),
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
        "videoFit", "swipeControls", "lastUpdateCheckAt", "lastUpdateNotice",
    )

    // Sync is optional: whatever goes wrong in it (no network, a revoked sign-in) must never close the app
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t -> System.err.println("Sync: $t") },
    )
    private val refreshLock = Mutex()
    // Holds the account's session: readable by this user only
    private val file = jsonFile<SyncStore>("sync.json", private = true) { SyncStore() }
    @Volatile
    private var store = file.read()
    private val lock = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var loop: Job? = null

    private val _ui = MutableStateFlow(
        SyncUiState(email = store.session?.email, lastSyncedAt = store.lastSyncedAt, askMerge = store.session != null && store.awaitingChoice)
    )
    val ui: StateFlow<SyncUiState> = _ui.asStateFlow()

    // Sign-in, sync passes and the settings screen all save from their own threads
    private val saveLock = Any()

    private fun save(update: (SyncStore) -> SyncStore) = synchronized(saveLock) {
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
                if (store.session != null && foreground) syncNow()
                withTimeoutOrNull(60_000) { wake.receive() }
            }
        }
    }

    /**
     * Whether the app is on screen. Android sets it (MainActivity onStart/onStop): in the background
     * the minute-by-minute passes stop, and coming back syncs at once.
     */
    @Volatile
    var foreground = true
        set(value) {
            field = value
            if (value) requestSync()
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
        SignInRedirect.stop()
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
        SignInRedirect.stop()
        val current = store.session?.userId
        // Another account than the one this device used before, with a library of its own: ask
        // before sending it there, instead of merging two people's libraries without a word
        val before = current ?: store.lastUserId
        val library = LibraryStore.state.value
        val switching = before != null && before != session.userId && (library.history.isNotEmpty() || library.bookmarks.isNotEmpty())
        save {
            // The link that signed in is used up: it can't sign this device in again
            if (current == session.userId) it.copy(session = session, pendingEmail = null, pendingAt = null)
            // A new account starts from this device's data; known items from another account don't apply
            else SyncStore(
                session = session.copy(email = session.email ?: email),
                lastUserId = session.userId,
                awaitingChoice = switching,
                declinedRepositories = if (before == session.userId) it.declinedRepositories else emptySet(),
            )
        }
        _ui.update { SyncUiState(email = session.email ?: email, choosingPassword = choosingPassword, askMerge = switching) }
        requestSync()
    }

    /**
     * After signing in to another account: [keepThisDevice] adds this device's library to it;
     * otherwise this device's progress, library, searches and remembered sources are replaced by
     * the account's.
     */
    fun chooseMerge(keepThisDevice: Boolean) = scope.launch {
        lock.withLock {
            if (!keepThisDevice) {
                LibraryStore.clearAll()
                SearchHistory.clear()
                SourceMemory.clear()
            }
            save { it.copy(awaitingChoice = false) }
        }
        _ui.update { it.copy(askMerge = false) }
        requestSync()
    }

    /** Adds the repositories the account's other devices use; their extensions then install here too. */
    fun addSuggestedRepositories() = scope.launch {
        _ui.value.suggestedRepositories.forEach { ExtensionManager.addRepositoryData(it) }
        _ui.update { it.copy(suggestedRepositories = emptyList()) }
        requestSync()
    }

    /** Leaves the other devices' repositories off this one, and stops suggesting them. */
    fun declineSuggestedRepositories() {
        val urls = _ui.value.suggestedRepositories.map { it.url }
        save { it.copy(declinedRepositories = it.declinedRepositories + urls) }
        _ui.update { it.copy(suggestedRepositories = emptyList()) }
    }

    fun cancelCode() {
        SignInRedirect.stop()
        save { it.copy(pendingEmail = null, pendingAt = null) }
        _ui.update { it.copy(codeSentTo = null, error = null) }
    }

    /** Stops syncing on this device. Its data stays as it is. */
    fun signOut() = scope.launch {
        val session = store.session
        lock.withLock { save { SyncStore(lastUserId = it.session?.userId ?: it.lastUserId) } }
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
            save { SyncStore(lastUserId = it.session?.userId ?: it.lastUserId) }
            _ui.value = SyncUiState(error = "Signed out of sync. Sign in again to keep syncing.")
            null
        }
    }

    private fun overlap(cursor: String): String =
        runCatching { java.time.OffsetDateTime.parse(cursor).minusSeconds(10).toString() }.getOrDefault(cursor)

    // ---------- Syncing ----------

    suspend fun syncNow() = lock.withLock {
        // Waiting for the user to say what happens to this device's library in a new account
        if (store.session == null || store.awaitingChoice) return@withLock
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
        val settingNames = syncedSettings(SettingsStore.state.value).keys
        // Removals from elsewhere that finish in the background (repositories, extensions): not
        // to be mistaken for local items in this pass's push
        val removedNow = mutableSetOf<String>()
        for (item in remote) {
            val id = "${item.kind}/${item.key}"
            val mine = local[id]
            if (item.deleted) {
                pending.remove(id)
                // Settings always have a value: a removal can only come from a version that didn't know the setting
                if (item.kind == SETTING || item.kind == SETTINGS) continue
                val myAt = mine?.at ?: known[id]?.at ?: 0
                if (mine != null && item.at >= myAt) {
                    removeLocal(item.kind, mine.rawKey)
                    removedNow += id
                }
                if (mine == null || item.at >= myAt) known.remove(id)
                continue
            }
            // A setting this version doesn't have: leave it to the devices that do
            if (item.kind == SETTING && item.key !in settingNames) continue
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
                val raw = mine?.rawKey ?: rawKey(item.kind, item.key, value) ?: continue
                when (applyLocal(item.kind, raw, value)) {
                    Applied.YES -> known[id] = KnownItem(hash, item.at)
                    Applied.LATER -> pending[id] = PendingItem(item.kind, raw, item.data, item.at)
                    // This version can't hold that value (a newer device's choice): keep ours, but don't
                    // send it back over theirs
                    Applied.NO -> if (mine != null) known[id] = KnownItem(hashOf(mine.data), item.at)
                }
            }
        }
        val cursor = remote.maxOfOrNull { it.serverTime } ?: store.cursor
        save { it.copy(cursor = cursor, known = known, pending = pending) }

        // Extensions waiting for their repository to load
        retryPending()
        updateSuggestions()

        // Push what changed here since the last pass
        val now = System.currentTimeMillis()
        val current = localItems()
        val changes = mutableListOf<LocalChange>()
        val next = store.known.toMutableMap()
        for ((id, item) in current) {
            if (id in store.pending || id in removedNow) continue
            val json = canonical.writeValueAsString(item.data)
            // Not worth a row, and too big for the server to take: this item stays on this device
            if (json.length > MAX_ITEM_CHARS) continue
            val hash = json.hashCode()
            val was = next[id]
            if (was == null || was.hash != hash) {
                val at = item.at ?: now
                changes += LocalChange(item.kind, item.key, item.data, deleted = false, at = at)
                next[id] = KnownItem(hash, at)
            }
        }
        // Gone since the last pass: a deletion. Not while a data file is unreadable, or its whole
        // contents would be deleted on every device. Never for settings, which are never removed:
        // one missing here is one this version doesn't have
        val deletions = if (DataHealth.unreadable.isEmpty()) next.keys - current.keys - store.pending.keys else emptySet()
        for (id in deletions) {
            val (kind, key) = id.split('/', limit = 2)
            if (kind == SETTING || kind == SETTINGS) continue
            changes += LocalChange(kind, key, null, deleted = true, at = now)
            next.remove(id)
        }
        if (changes.isNotEmpty()) {
            SupabaseClient.push(session, changes)
            save { it.copy(known = next) }
        }
    }

    /**
     * [key] is the item's key as sent; [rawKey] is the same key as this device's stores use. They
     * differ only for very long keys, which are sent as a hash (see [syncKey]).
     */
    private data class LocalItem(val kind: String, val rawKey: String, val data: Any, val at: Long?) {
        val key: String get() = syncKey(rawKey)
    }

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

    /**
     * The key this device's stores use for a hashed [key] from another device, taken from the item
     * itself. Null when it can't be told (a remembered source for a title this device doesn't have).
     */
    private fun rawKey(kind: String, key: String, value: Any): String? {
        if (!key.startsWith(HASHED_KEY)) return key
        @Suppress("UNCHECKED_CAST")
        val raw = when (kind) {
            HISTORY -> LibraryStore.historyKey(value as HistoryEntry)
            BOOKMARK -> LibraryStore.bookmarkKey(value as Bookmark)
            SEARCH -> SearchHistory.key((value as SearchHistoryEntry).query)
            REPOSITORY -> (value as RepositoryData).url
            EXTENSION -> (value as Map<String, Any?>).let { "${it["repositoryUrl"]}|${it["internalName"]}" }
            else -> null
        }
        return raw?.takeIf { syncKey(it) == key }
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

    private enum class Applied { YES, LATER, NO }

    /** Applies another device's version here: done, not yet (try again on later passes), or not possible in this version. */
    @Suppress("UNCHECKED_CAST")
    private suspend fun applyLocal(kind: String, key: String, value: Any): Applied = when (kind) {
        HISTORY -> { LibraryStore.applyRemoteHistory(key, value as HistoryEntry); Applied.YES }
        BOOKMARK -> { LibraryStore.applyRemoteBookmark(key, value as Bookmark); Applied.YES }
        SOURCE -> { SourceMemory.applyRemote(key, value as RememberedSource); Applied.YES }
        SETTINGS -> { mergeSettings(value as Map<String, Any?>); Applied.YES }
        SETTING -> when {
            key in deviceSettings -> Applied.YES
            mergeSettings(mapOf(key to (value as Map<String, Any?>)["value"])) -> Applied.YES
            else -> Applied.NO
        }
        // Only repositories the user added on this device, or agreed to add (see suggestedRepositories)
        REPOSITORY -> if (ExtensionManager.repositories().any { it.url == (value as RepositoryData).url }) Applied.YES else Applied.LATER
        EXTENSION -> if (installExtension(value as Map<String, Any?>)) Applied.YES else Applied.LATER
        SEARCH -> { SearchHistory.applyRemote(key, value as SearchHistoryEntry); Applied.YES }
        else -> Applied.YES
    }

    /**
     * Puts [remote] settings over this device's, leaving the rest as they are. False when one of
     * them isn't a setting this version has, or has a value it can't hold.
     */
    private fun mergeSettings(remote: Map<String, Any?>): Boolean {
        var all = true
        SettingsStore.update { current ->
            var next = current
            for ((name, value) in remote) {
                val merged = jsonMapper.convertValue(next, object : TypeReference<MutableMap<String, Any?>>() {})
                if (name !in merged) { all = false; continue }
                merged[name] = value
                next = runCatching { jsonMapper.convertValue(merged, AppSettings::class.java) }.getOrElse { all = false; next }
            }
            next
        }
        return all
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
            when (applyLocal(item.kind, item.key, value)) {
                Applied.YES -> {
                    pending.remove(id)
                    known[id] = KnownItem(hashOf(value), item.at)
                }
                Applied.NO -> pending.remove(id)
                Applied.LATER -> Unit
            }
        }
        save { it.copy(pending = pending, known = known) }
    }

    /** Repositories other devices added that wait for the user here, shown in Settings, Sync. */
    private fun updateSuggestions() {
        val added = ExtensionManager.repositories().map { it.url }.toSet()
        val suggested = store.pending.values.filter { it.kind == REPOSITORY }
            .mapNotNull { decode(it.kind, it.data) as? RepositoryData }
            .filter { it.url !in added && it.url !in store.declinedRepositories }
            .distinctBy { it.url }
        val before = _ui.value.suggestedRepositories.map { it.url }.toSet()
        _ui.update { it.copy(suggestedRepositories = suggested) }
        if (suggested.any { it.url !in before }) {
            com.lagradost.cloudstream4.desktop.DesktopToasts.show(
                "Your other devices use ${suggested.size} ${if (suggested.size == 1) "repository" else "repositories"} not added here. Open Settings, Sync to add them."
            )
        }
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

    /** Items larger than this (as JSON) stay on their device; the server refuses rows much larger. */
    private const val MAX_ITEM_CHARS = 64 * 1024
}

/** Marks a key sent as its hash. */
private const val HASHED_KEY = "sha256:"

/**
 * The key an item is stored under on the server. Keys are mostly short, but an episode's data
 * can be long, and the server's index can't hold keys of a few KB: those go as their hash.
 */
internal fun syncKey(raw: String): String {
    if (raw.length <= 512) return raw
    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
    return HASHED_KEY + digest.joinToString("") { "%02x".format(it) }
}
