package dev.gray.companion.agent

import android.content.Context
import dev.gray.companion.core.CredentialKind
import dev.gray.companion.core.CredentialSlot
import dev.gray.companion.core.CredentialStore
import dev.gray.companion.data.KeystoreCredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit

data class AgentAuthState(
    val loading: Boolean = false,
    val signingIn: Boolean = false,
    val signedIn: Boolean = false,
    val email: String? = null,
    val subject: String? = null,
    val planUsageAllowed: Boolean = false,
    val error: String? = null,
    val loginUrl: String? = null,
)

/** Owns a separate SIWC registration and OAuth session for the local Agent. */
class AgentAuth(
    context: Context,
    private val scope: CoroutineScope,
    private val credentials: CredentialStore = KeystoreCredentialStore(context),
) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("agent-siwc", Context.MODE_PRIVATE)
    private val slot = CredentialSlot("builtin.agent.siwc", CredentialKind.PLUGIN_SECRET)
    private val json = Json { ignoreUnknownKeys = true }
    private val tokenMutex = Mutex()
    private val pendingGuard = Any()
    private var pending: Pending? = null
    private val random = SecureRandom()
    private val http = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).build()

    private val mutableState = MutableStateFlow(AgentAuthState())
    val state: StateFlow<AgentAuthState> = mutableState

    /** Starts the loopback server before returning the browser URL. */
    suspend fun beginSignIn(): String = startSignIn(newAccount = false)

    /** Explicit account switch: a saved issued client ID is bound to its original account/workspace. */
    suspend fun beginSignInNewAccount(): String = startSignIn(newAccount = true)

    private suspend fun startSignIn(newAccount: Boolean): String = withContext(Dispatchers.IO) {
        val existing = tokenMutex.withLock { readRecord() }
        val hostId = prefs.getString("host_id", null) ?: "urn:uuid:${UUID.randomUUID()}".also {
            check(prefs.edit().putString("host_id", it).commit())
        }
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply { soTimeout = 1_000 }
        val clientId = if (newAccount) null else existing?.clientId
        val attempt = Pending(
            server = server,
            state = randomUrlValue(),
            nonce = randomUrlValue(),
            verifier = randomUrlValue(64),
            expectedSubject = if (newAccount) null else existing?.subject,
            savedClientId = clientId,
            redirectUri = "http://127.0.0.1:${server.localPort}/auth/callback",
        )
        synchronized(pendingGuard) {
            if (pending != null) {
                server.close()
                error("ChatGPT sign-in is already in progress")
            }
            pending = attempt
        }
        val fields = linkedMapOf(
            "client_id" to (clientId ?: "dynamic_agent_client"),
            "ext_agent_host_id" to hostId,
            "response_type" to "code",
            "redirect_uri" to attempt.redirectUri,
            "scope" to "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct",
            "resource" to RESOURCE,
            "state" to attempt.state,
            "nonce" to attempt.nonce,
            "code_challenge_method" to "S256",
            "code_challenge" to base64Url(MessageDigest.getInstance("SHA-256")
                .digest(attempt.verifier.toByteArray(Charsets.US_ASCII))),
        )
        if (clientId == null) fields["agent_name_hint"] = APP_NAME
        else {
            existing?.idToken?.takeIf { it.isNotBlank() }?.let { fields["id_token_hint"] = it }
            existing?.email?.takeIf { it.isNotBlank() }?.let { fields["login_hint"] = it }
        }
        val url = "https://auth.openai.com/api/accounts/authorize?" +
            fields.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        mutableState.value = mutableState.value.copy(signingIn = true, error = null, loginUrl = url)
        attempt.job = scope.launch(Dispatchers.IO) { listen(attempt) }
        url
    }

    fun cancelSignIn() {
        val old = synchronized(pendingGuard) { pending.also { pending = null } }
        old?.server?.close()
        old?.job?.cancel()
        if (old != null) mutableState.value = mutableState.value.copy(signingIn = false, loginUrl = null)
    }

    suspend fun reload() = withContext(Dispatchers.IO) {
        mutableState.value = mutableState.value.copy(loading = true)
        try {
            val record = tokenMutex.withLock { readRecord() }
            val connected = record != null && record.refreshToken.isNotBlank() && record.accessToken.isNotBlank()
            mutableState.value = mutableState.value.copy(
                loading = false, signedIn = connected,
                email = record?.email, subject = record?.subject,
                planUsageAllowed = connected && record?.scopes?.contains(DIRECT_SCOPE) == true, error = null,
            )
        } catch (_: Exception) {
            mutableState.value = mutableState.value.copy(loading = false, signedIn = false,
                planUsageAllowed = false, error = "Saved ChatGPT connection is unavailable")
        }
    }

    /** Returns a bearer token only when this account granted direct plan use. */
    suspend fun accessToken(forceRefresh: Boolean = false): String = withContext(Dispatchers.IO) {
        tokenMutex.withLock {
            var record = readRecord() ?: error("Connect ChatGPT to use Agent")
            if (record.refreshToken.isBlank() || record.accessToken.isBlank()) error("Connect ChatGPT to use Agent")
            if (!record.scopes.contains(DIRECT_SCOPE)) error("ChatGPT plan use is not enabled")
            val now = Instant.now().epochSecond
            if (forceRefresh || record.expiresAt <= now + 60) {
                try {
                    record = refresh(record)
                    saveRecord(record)
                    mutableState.value = mutableState.value.copy(
                        signedIn = true, planUsageAllowed = record.scopes.contains(DIRECT_SCOPE), error = null)
                } catch (failure: OAuthFailure) {
                    if (failure.code in TERMINAL_REFRESH_ERRORS) {
                        saveRecord(record.withoutTokens())
                        mutableState.value = mutableState.value.copy(signedIn = false,
                            planUsageAllowed = false, error = "ChatGPT sign-in expired; connect again")
                    }
                    throw IllegalStateException("ChatGPT connection could not be renewed")
                }
            }
            if (!record.scopes.contains(DIRECT_SCOPE)) error("ChatGPT plan use is not enabled")
            record.accessToken
        }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        cancelSignIn()
        tokenMutex.withLock {
            val record = readRecord()
            var revocationUnconfirmed = false
            if (record != null) {
                if (record.refreshToken.isNotBlank()) {
                    var revoked = false
                    for (attempt in 0..2) {
                        try {
                            revoke(record)
                            revoked = true
                            break
                        } catch (_: IOException) {
                            if (attempt < 2) delay(300L shl attempt)
                        } catch (_: Exception) {
                            break
                        }
                    }
                    revocationUnconfirmed = !revoked
                }
                saveRecord(record.withoutTokens())
            }
            mutableState.value = AgentAuthState(
                email = record?.email, subject = record?.subject,
                error = if (revocationUnconfirmed) "Signed out locally; remote revocation was not confirmed" else null,
            )
        }
    }

    private suspend fun listen(attempt: Pending) {
        try {
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5)
            while (System.nanoTime() < deadline) {
                currentCoroutineContext().ensureActive()
                if (!isPending(attempt)) return
                val socket = try { attempt.server.accept() } catch (_: SocketTimeoutException) { continue }
                socket.use { handleCallback(it, attempt) }
                if (attempt.accepted) return
            }
            finish(attempt, "ChatGPT sign-in timed out")
        } catch (_: CancellationException) {
            // User canceled this attempt.
        } catch (_: Exception) {
            finish(attempt, "ChatGPT sign-in could not be completed")
        } finally {
            attempt.server.close()
        }
    }

    private suspend fun handleCallback(socket: Socket, attempt: Pending) {
        socket.soTimeout = 3_000
        val request = try { readRequest(socket) } catch (_: Exception) { null }
        val target = request?.takeIf { it.startsWith("GET /auth/callback?") }
            ?.substringAfter("GET ")?.substringBefore(" HTTP/")
        val params = target?.let {
            AgentOAuth.callback("http://127.0.0.1:${attempt.server.localPort}$it",
                attempt.server.localPort, attempt.state)
        }
        if (params == null) {
            reply(socket, 400, "Sign-in response was not accepted. Return to the app and try again.")
            return
        }
        attempt.accepted = true
        if (params.containsKey("error")) {
            runCatching { reply(socket, 200, "ChatGPT authorization was canceled. Return to the app.") }
            finish(attempt, if (params["error"] == "access_denied")
                "ChatGPT authorization was declined" else "ChatGPT authorization failed")
            return
        }
        val code = params["code"]?.takeIf { it.isNotBlank() }
        val clientId = if (attempt.savedClientId == null) params["client_id"]?.takeIf { it.isNotBlank() }
            else attempt.savedClientId.takeIf { params["client_id"] == null || params["client_id"] == it }
        if (code == null || clientId == null || !clientId.startsWith("oaiapp_")) {
            reply(socket, 400, "ChatGPT registration was incomplete. Return to the app.")
            finish(attempt, "ChatGPT registration was incomplete")
            return
        }
        runCatching { reply(socket, 200, "Authorization received. Return to the app.") }
        try {
            // Retain the issued registration even if a one-time code has expired.
            tokenMutex.withLock {
                synchronized(pendingGuard) {
                    if (pending !== attempt) return
                    val prior = readRecord()
                    if (attempt.savedClientId == null && prior == null)
                        saveRecord(AgentCredential(clientId = clientId))
                }
            }
            val tokens = exchange(code, clientId, attempt)
            val identity = AgentOAuth.verifyIdToken(tokens.idToken,
                fetchJwks(), clientId, attempt.nonce)
            require(attempt.expectedSubject == null || identity.subject == attempt.expectedSubject) {
                "ChatGPT account changed"
            }
            val record = AgentCredential(
                clientId = clientId, subject = identity.subject, email = identity.email,
                idToken = tokens.idToken, accessToken = tokens.accessToken,
                refreshToken = tokens.refreshToken, expiresAt = Instant.now().epochSecond + tokens.expiresIn,
                scopes = tokens.scopes,
            )
            tokenMutex.withLock {
                synchronized(pendingGuard) {
                    if (pending !== attempt) return
                    saveRecord(record)
                    pending = null
                }
            }
            mutableState.value = AgentAuthState(signedIn = true, email = record.email,
                subject = record.subject, planUsageAllowed = record.scopes.contains(DIRECT_SCOPE))
        } catch (_: Exception) {
            finish(attempt, "ChatGPT connection could not be verified")
        }
    }

    private fun finish(attempt: Pending, message: String) {
        synchronized(pendingGuard) {
            if (pending !== attempt) return
            pending = null
        }
        mutableState.value = mutableState.value.copy(signingIn = false, loginUrl = null, error = message)
    }

    private fun isPending(attempt: Pending): Boolean = synchronized(pendingGuard) { pending === attempt }

    private fun readRequest(socket: Socket): String? {
        val bytes = ByteArrayOutputStream()
        var previous = 0
        var previous2 = 0
        var previous3 = 0
        while (bytes.size() < 8_192) {
            val next = socket.getInputStream().read()
            if (next < 0) return null
            bytes.write(next)
            if (previous3 == 13 && previous2 == 10 && previous == 13 && next == 10) break
            previous3 = previous2; previous2 = previous; previous = next
        }
        if (bytes.size() >= 8_192) return null
        val raw = bytes.toString(Charsets.US_ASCII.name())
        val line = raw.substringBefore("\r\n")
        return line.takeIf { raw.endsWith("\r\n\r\n") && it.length <= 4_096 && it.endsWith(" HTTP/1.1") }
    }

    private fun reply(socket: Socket, status: Int, message: String) {
        val body = "<html><body><p>${message}</p></body></html>".toByteArray(Charsets.UTF_8)
        val header = "HTTP/1.1 $status ${if (status == 200) "OK" else "Bad Request"}\r\n" +
            "Content-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
            "Cache-Control: no-store\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().write(header.toByteArray(Charsets.US_ASCII) + body)
    }

    private fun exchange(code: String, clientId: String, attempt: Pending): TokenData {
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code").add("client_id", clientId)
            .add("code", code).add("code_verifier", attempt.verifier)
            .add("redirect_uri", attempt.redirectUri).add("resource", RESOURCE).build()
        return parseTokens(postToken(body), requireIdentity = true)
    }

    private fun refresh(record: AgentCredential): AgentCredential {
        val body = FormBody.Builder().add("grant_type", "refresh_token")
            .add("client_id", record.clientId).add("refresh_token", record.refreshToken)
            .add("resource", RESOURCE).build()
        val tokens = parseTokens(postToken(body), requireIdentity = false)
        return record.copy(accessToken = tokens.accessToken, refreshToken = tokens.refreshToken,
            expiresAt = Instant.now().epochSecond + tokens.expiresIn,
            scopes = tokens.scopes.ifEmpty { record.scopes })
    }

    private fun revoke(record: AgentCredential) {
        val config = getJson("https://auth.openai.com/.well-known/openid-configuration")
        val endpoint = config.string("revocation_endpoint")
        require(endpoint.startsWith("https://auth.openai.com/"))
        val body = FormBody.Builder().add("token", record.refreshToken)
            .add("token_type_hint", "refresh_token").add("client_id", record.clientId).build()
        http.newCall(Request.Builder().url(endpoint).post(body).build()).execute().use {
            if (it.code >= 500) throw IOException("Revocation temporarily unavailable")
            if (!it.isSuccessful) throw IllegalStateException("Revocation unavailable")
        }
    }

    private fun postToken(body: FormBody): JsonObject {
        val request = Request.Builder().url("https://auth.openai.com/api/accounts/oauth/token")
            .header("Accept", "application/json").post(body).build()
        http.newCall(request).execute().use { response ->
            val text = response.body?.byteStream()?.let { readLimited(it, 65_536) }.orEmpty()
            val parsed = try { json.parseToJsonElement(text).jsonObject } catch (_: Exception) { null }
            if (!response.isSuccessful) throw OAuthFailure(parsed?.stringOrNull("error"))
            return parsed ?: error("Invalid ChatGPT token response")
        }
    }

    private fun fetchJwks(): String {
        val config = getJson("https://auth.openai.com/.well-known/openid-configuration")
        require(config.string("issuer") == "https://auth.openai.com")
        val url = config.string("jwks_uri")
        require(url.startsWith("https://auth.openai.com/"))
        val request = Request.Builder().url(url).get().build()
        http.newCall(request).execute().use {
            if (!it.isSuccessful) error("Identity keys unavailable")
            return it.body?.byteStream()?.let { stream -> readLimited(stream, 131_072) }
                ?: error("Identity keys unavailable")
        }
    }

    private fun getJson(url: String): JsonObject {
        http.newCall(Request.Builder().url(url).get().build()).execute().use {
            if (!it.isSuccessful) error("OpenAI configuration unavailable")
            val text = it.body?.byteStream()?.let { stream -> readLimited(stream, 65_536) }
                ?: error("OpenAI configuration unavailable")
            return json.parseToJsonElement(text).jsonObject
        }
    }

    private fun parseTokens(raw: JsonObject, requireIdentity: Boolean): TokenData {
        val access = raw.string("access_token")
        val refresh = raw.string("refresh_token")
        val id = raw.stringOrNull("id_token").orEmpty()
        if (requireIdentity) require(id.isNotBlank())
        require(raw.string("token_type").equals("Bearer", ignoreCase = true))
        val expires = raw.string("expires_in").toLongOrNull() ?: error("Invalid token expiry")
        require(expires in 1..86_400)
        return TokenData(access, refresh, id, expires,
            raw.stringOrNull("scope")?.split(' ')?.filter { it.isNotBlank() }?.toSet() ?: emptySet())
    }

    private fun readLimited(stream: InputStream, maxBytes: Int): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4_096)
        while (true) {
            val count = stream.read(buffer, 0, minOf(buffer.size, maxBytes + 1 - output.size()))
            if (count < 0) break
            output.write(buffer, 0, count)
            require(output.size() <= maxBytes) { "OpenAI response too large" }
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun readRecord(): AgentCredential? = credentials.get(slot).takeIf { it.isNotBlank() }
        ?.let { json.decodeFromString<AgentCredential>(it) }
    private fun saveRecord(record: AgentCredential) = credentials.put(slot, json.encodeToString(record))
    private fun randomUrlValue(bytes: Int = 32): String = ByteArray(bytes).also(random::nextBytes).let(::base64Url)
    private fun base64Url(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun JsonObject.string(key: String): String = stringOrNull(key) ?: error("Invalid OpenAI response")
    private fun JsonObject.stringOrNull(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private data class Pending(
        val server: ServerSocket, val state: String, val nonce: String, val verifier: String,
        val expectedSubject: String?, val savedClientId: String?, val redirectUri: String,
        var job: Job? = null, var accepted: Boolean = false,
    )
    private data class TokenData(val accessToken: String, val refreshToken: String,
        val idToken: String, val expiresIn: Long, val scopes: Set<String>)
    private class OAuthFailure(val code: String?) : IllegalStateException("ChatGPT token request failed")

    private companion object {
        const val RESOURCE = "https://api.openai.com/v1"
        const val DIRECT_SCOPE = "chatgpt.tokens.use.direct"
        const val APP_NAME = "velune"
        val TERMINAL_REFRESH_ERRORS = setOf("invalid_grant", "invalid_refresh_token", "token_expired",
            "refresh_token_expired", "refresh_token_invalidated", "refresh_token_reused")
    }
}

@Serializable
private data class AgentCredential(
    val clientId: String,
    val subject: String? = null,
    val email: String? = null,
    val idToken: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    val expiresAt: Long = 0,
    val scopes: Set<String> = emptySet(),
) {
    fun withoutTokens() = copy(idToken = "", accessToken = "", refreshToken = "", expiresAt = 0,
        scopes = emptySet())
}
