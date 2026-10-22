package com.lagradost.cloudstream4.desktop.sync

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream4.desktop.data.jsonMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/** The Supabase project the apps sync with. The publishable key is meant to ship in apps; row level security protects the data. */
object SyncConfig {
    /** Tests point this at a local fake server with -Dhyperstream.syncUrl. */
    val URL: String get() = System.getProperty("hyperstream.syncUrl") ?: "https://fdpxrquqdrncprllembu.supabase.co"
    const val KEY = "sb_publishable_CHMPNtueQqYmOSh73IOVgQ_GPq85-v-"
}

/** A signed-in session, saved on the device. */
data class SyncSession(
    @JsonProperty("accessToken") val accessToken: String,
    @JsonProperty("refreshToken") val refreshToken: String,
    /** When [accessToken] stops working, in ms. */
    @JsonProperty("expiresAt") val expiresAt: Long,
    @JsonProperty("userId") val userId: String,
    @JsonProperty("email") val email: String?,
)

/** One stored item as the server returns it. */
data class RemoteItem(
    val kind: String,
    val key: String,
    val data: JsonNode?,
    val deleted: Boolean,
    val at: Long,
    val serverTime: String,
)

/** A change to send: [data] null with [deleted] for removals. */
data class LocalChange(val kind: String, val key: String, val data: Any?, val deleted: Boolean, val at: Long)

class SyncAuthException(message: String) : IOException(message)

/**
 * Talks to Supabase's REST endpoints directly (auth and the database API), with the same HTTP
 * client the extensions use, so the Mac, phone and TV share it without an SDK.
 */
object SupabaseClient {
    private val JSON = "application/json".toMediaType()

    private fun request(path: String) = Request.Builder().url("${SyncConfig.URL}$path").header("apikey", SyncConfig.KEY)

    private suspend fun call(builder: Request.Builder): JsonNode? = withContext(Dispatchers.IO) {
        app.baseClient.newCall(builder.build()).execute().use { response ->
            val text = response.body.string()
            val json = runCatching { jsonMapper.readTree(text) }.getOrNull()
            if (!response.isSuccessful) {
                val message = json?.let { j ->
                    listOf("msg", "message", "error_description", "error").firstNotNullOfOrNull { j.get(it)?.asText()?.takeIf { t -> t.isNotBlank() } }
                } ?: "The sync server answered ${response.code}."
                // A refresh token the server no longer knows (signed out, or used already) is a 400
                val code = json?.get("error_code")?.asText().orEmpty()
                if (response.code == 401 || response.code == 403 || code.startsWith("refresh_token") || code == "session_not_found") {
                    throw SyncAuthException(message)
                }
                throw IOException(message)
            }
            json
        }
    }

    private fun body(value: Any) = jsonMapper.writeValueAsString(value).toRequestBody(JSON)

    /**
     * Emails a sign-in link to [email], creating the account the first time. The link returns to
     * [redirectTo] (the app itself) with the session, so opening it signs the app in.
     */
    suspend fun sendLink(email: String, redirectTo: String?) {
        val path = "/auth/v1/otp" + (redirectTo?.let { "?redirect_to=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
        call(request(path).post(body(mapOf("email" to email, "create_user" to true))))
    }

    /**
     * The session from where a sign-in link returned: its address carries the tokens after "#"
     * (access_token, refresh_token, expires_in), or an error description.
     */
    suspend fun sessionFromRedirect(link: String): SyncSession {
        val params = (link.substringAfter('#', "").ifEmpty { link.substringAfter('?', "") }).split('&')
            .mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to java.net.URLDecoder.decode(it[1], "UTF-8") } }
            .toMap()
        params["error_description"]?.let { throw IOException(it.replace('+', ' ')) }
        val access = params["access_token"] ?: throw IOException("The link has no sign-in in it. Copy the whole address.")
        val user = call(request("/auth/v1/user").header("Authorization", "Bearer $access").get())
        return SyncSession(
            accessToken = access,
            refreshToken = params["refresh_token"].orEmpty(),
            expiresAt = System.currentTimeMillis() + (params["expires_in"]?.toLongOrNull() ?: 3600) * 1000,
            userId = user?.get("id")?.asText().orEmpty(),
            email = user?.get("email")?.asText(),
        )
    }

    /**
     * Emails a link for choosing a new password. Like a sign-in link it returns to [redirectTo] with
     * a session, marked type=recovery, and the app then asks for the new password.
     */
    suspend fun sendPasswordReset(email: String, redirectTo: String?) {
        val path = "/auth/v1/recover" + (redirectTo?.let { "?redirect_to=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")
        call(request(path).post(body(mapOf("email" to email))))
    }

    /** Creates an account with a password. Email confirmation is off in the project, so it's ready at once. */
    suspend fun signUp(email: String, password: String): SyncSession {
        val result = call(request("/auth/v1/signup").post(body(mapOf("email" to email, "password" to password))))
        if (result?.get("access_token") == null) throw IOException("Account created. Confirm it from the email Supabase sent, then sign in.")
        return session(result, email)
    }

    suspend fun signInWithPassword(email: String, password: String): SyncSession =
        session(call(request("/auth/v1/token?grant_type=password").post(body(mapOf("email" to email, "password" to password)))), email)

    /** Sets or changes this account's password, so it can sign in with one on other devices. */
    suspend fun setPassword(session: SyncSession, password: String) {
        call(request("/auth/v1/user").header("Authorization", "Bearer ${session.accessToken}").put(body(mapOf("password" to password))))
    }

    /** A one-time 8-digit code that signs a TV in to this account, for 10 minutes. */
    suspend fun createTvCode(session: SyncSession): String =
        call(request("/rest/v1/rpc/hs_create_tv_code").header("Authorization", "Bearer ${session.accessToken}").post(body(emptyMap<String, String>())))
            ?.asText()?.takeIf { it.isNotBlank() } ?: throw IOException("The server sent no code.")

    /** Signs a TV in with the code a signed-in phone or Mac showed. */
    suspend fun signInWithTvCode(code: String): SyncSession {
        val result = call(request("/functions/v1/tv-sign-in").post(body(mapOf("code" to code))))
        val hash = result?.get("token_hash")?.asText() ?: throw IOException("That code didn't work. Show a new one.")
        return session(call(request("/auth/v1/verify").post(body(mapOf("type" to "magiclink", "token_hash" to hash)))), result.get("email")?.asText())
    }

    /** Signs in with the code from the email. */
    suspend fun verifyCode(email: String, code: String): SyncSession =
        session(call(request("/auth/v1/verify").post(body(mapOf("type" to "email", "email" to email, "token" to code)))))

    /** Signs in with the link from the email, pasted in: its token, without opening it in a browser. */
    suspend fun verifyLink(link: String): SyncSession {
        // Already opened in a browser: the address it ended on has the session
        if ("access_token=" in link || "error_description=" in link) return sessionFromRedirect(link.trim())
        val url = runCatching { link.trim().toHttpUrl() }.getOrNull() ?: throw IOException("That doesn't look like the sign-in link.")
        val token = url.queryParameter("token") ?: throw IOException("The link has no sign-in token. Copy the whole link from the email.")
        val type = url.queryParameter("type") ?: "magiclink"
        return session(call(request("/auth/v1/verify").post(body(mapOf("type" to type, "token_hash" to token)))))
    }

    /** Throws [SyncAuthException] when the server refuses the refresh token, and IOException when it can't be reached. */
    suspend fun refresh(session: SyncSession): SyncSession =
        session(call(request("/auth/v1/token?grant_type=refresh_token").post(body(mapOf("refresh_token" to session.refreshToken)))), session.email)

    suspend fun signOut(session: SyncSession) {
        // Only this device: Supabase's default scope signs every device out of the account
        runCatching { call(request("/auth/v1/logout?scope=local").header("Authorization", "Bearer ${session.accessToken}").post(body(emptyMap<String, String>()))) }
    }

    private fun session(json: JsonNode?, fallbackEmail: String? = null): SyncSession {
        json ?: throw IOException("The sync server sent no session.")
        val access = json.get("access_token")?.asText() ?: throw IOException("Sign-in didn't work. Ask for a new code.")
        val expiresIn = json.get("expires_in")?.asLong() ?: 3600
        val user = json.get("user")
        return SyncSession(
            accessToken = access,
            refreshToken = json.get("refresh_token")?.asText().orEmpty(),
            expiresAt = System.currentTimeMillis() + expiresIn * 1000,
            userId = user?.get("id")?.asText().orEmpty(),
            email = user?.get("email")?.asText() ?: fallbackEmail,
        )
    }

    /** Sends changes; the server keeps whichever version of each item is newer. */
    suspend fun push(session: SyncSession, changes: List<LocalChange>) {
        changes.chunked(200).forEach { batch ->
            val items = batch.map { mapOf("kind" to it.kind, "key" to it.key, "data" to it.data, "deleted" to it.deleted, "at" to it.at) }
            call(request("/rest/v1/rpc/hs_push").header("Authorization", "Bearer ${session.accessToken}").post(body(mapOf("items" to items))))
        }
    }

    /**
     * Everything stored since [since] (server time), oldest first, a page at a time. Pages follow
     * on from the last row's time rather than a fixed offset into the whole set, which shifted
     * (skipping rows) when another device saved during a long pull. Rows saved together share one
     * time, so within that time it skips the rows it already has.
     */
    suspend fun pull(session: SyncSession, since: String?): List<RemoteItem> {
        val all = mutableListOf<RemoteItem>()
        val pageSize = 500
        var from = since
        var seenAtFrom = 0
        while (true) {
            val url = "${SyncConfig.URL}/rest/v1/hs_sync_items".toHttpUrl().newBuilder()
                .addQueryParameter("select", "kind,item_key,data,deleted,client_updated_at,updated_at")
                .addQueryParameter("order", "updated_at.asc,kind.asc,item_key.asc")
                .addQueryParameter("limit", "$pageSize")
                .addQueryParameter("offset", "$seenAtFrom")
                .apply { if (from != null) addQueryParameter("updated_at", "gte.$from") }
                .build()
            val page = call(Request.Builder().url(url).header("apikey", SyncConfig.KEY).header("Authorization", "Bearer ${session.accessToken}").get())
                ?.map { row ->
                    RemoteItem(
                        kind = row.get("kind").asText(),
                        key = row.get("item_key").asText(),
                        data = row.get("data")?.takeUnless { it.isNull },
                        deleted = row.get("deleted")?.asBoolean() == true,
                        at = row.get("client_updated_at").asLong(),
                        serverTime = row.get("updated_at").asText(),
                    )
                }.orEmpty()
            all += page
            if (page.size < pageSize) break
            val last = page.last().serverTime
            val atLast = page.count { it.serverTime == last }
            seenAtFrom = if (last == from) seenAtFrom + atLast else atLast
            from = last
        }
        return all
    }
}
