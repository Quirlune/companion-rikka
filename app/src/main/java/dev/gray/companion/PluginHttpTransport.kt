package dev.gray.companion

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PluginTransportRequest(
    val url: String, val method: String, val headers: Map<String, String>, val body: String?,
    val timeoutMs: Long, val maxResponseBytes: Int, val secret: String?
)
data class PluginHttpResponse(val status: Int, val headers: Map<String, String>, val body: String)

/** Dedicated direct client; each request pins one validated DNS answer for all of its connections. */
open class PluginHttpTransport(private val fakeIpHosts: Set<String> = emptySet()) {
    open suspend fun execute(request: PluginTransportRequest): PluginHttpResponse {
        val url = request.url.toHttpUrlOrNull()
            ?: throw PluginHostFailure("INVALID_URL", "仅允许规范 HTTPS URL")
        if (url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            request.url.contains('#') || request.url.contains('\\'))
            throw PluginHostFailure("INVALID_URL", "仅允许规范 HTTPS URL")
        val addresses = try { withContext(Dispatchers.IO) { InetAddress.getAllByName(url.host).toList() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw PluginHostFailure("NETWORK_ERROR", "目标地址解析失败") }
        if (addresses.isEmpty() || addresses.any { !isPublicAddress(it) &&
                !(url.host in fakeIpHosts && isProxyFakeAddress(it)) })
            throw PluginHostFailure("DESTINATION_DENIED", "目标地址不可访问")
        val pinnedDns = Dns { hostname ->
            if (!hostname.equals(url.host, ignoreCase = true)) throw java.net.UnknownHostException("Host mismatch")
            addresses
        }
        val client = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .dns(pinnedDns)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .callTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
            .connectTimeout(minOf(request.timeoutMs, 15_000), TimeUnit.MILLISECONDS)
            .readTimeout(minOf(request.timeoutMs, 30_000), TimeUnit.MILLISECONDS)
            .build()
        try {
            val builder = Request.Builder().url(url)
            request.headers.forEach { (name, value) -> builder.header(name, value) }
            val contentType = request.headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value
            val mediaType = if (contentType == null) "text/plain; charset=utf-8".toMediaType()
                else contentType.toMediaTypeOrNull()
                    ?: throw PluginHostFailure("INVALID_HEADER", "Content-Type 请求头无效")
            val body = if (request.body != null || request.method in setOf("POST", "PUT", "PATCH"))
                (request.body ?: "").toRequestBody(mediaType) else null
            val call = client.newCall(builder.method(request.method, body).build())
            try {
                return suspendCancellableCoroutine { continuation ->
                    continuation.invokeOnCancellation { call.cancel() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            if (continuation.isActive) continuation.resumeWithException(
                                PluginHostFailure("NETWORK_ERROR", "HTTP 传输失败，远端处理结果不确定", delivery = "unknown"))
                        }
                        override fun onResponse(call: Call, response: Response) {
                            try {
                                val result = response.use { readResponse(it, request) }
                                if (continuation.isActive) continuation.resume(result)
                            } catch (failure: PluginHostFailure) {
                                if (continuation.isActive) continuation.resumeWithException(failure)
                            } catch (_: Exception) {
                                if (continuation.isActive) continuation.resumeWithException(
                                    PluginHostFailure("NETWORK_ERROR", "HTTP 响应读取失败", delivery = "received"))
                            }
                        }
                    })
                }
            } catch (cancelled: CancellationException) {
                call.cancel()
                throw cancelled
            }
        } finally {
            // A request-scoped pool must not retain idle sockets or worker threads.
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    private fun readResponse(result: Response, request: PluginTransportRequest): PluginHttpResponse {
        val stream = result.body?.byteStream()
        val sink = java.io.ByteArrayOutputStream(minOf(request.maxResponseBytes, 8192))
        if (stream != null) {
            val chunk = ByteArray(8192)
            while (true) {
                val count = stream.read(chunk)
                if (count < 0) break
                if (sink.size() + count > request.maxResponseBytes)
                    throw PluginHostFailure("RESPONSE_TOO_LARGE", "HTTP 响应超出限额", delivery = "received")
                sink.write(chunk, 0, count)
            }
        }
        val redactedBody = redact(sink.toString(Charsets.UTF_8.name()), request.secret)
        val safeHeaders = result.headers.names().asSequence()
            .filterNot { it.lowercase(Locale.ROOT) in sensitiveResponseHeaders }
            .associateWith { name -> redact(result.header(name).orEmpty(), request.secret) }
        return PluginHttpResponse(result.code, safeHeaders, redactedBody)
    }

    companion object {
        // Explicit native-service opt-in only. Plugin requests keep the public-address policy.
        internal fun isProxyFakeAddress(address: InetAddress): Boolean {
            val bytes = address.address
            return address is Inet4Address && (bytes[0].toInt() and 255) == 198 &&
                (bytes[1].toInt() and 255) in 18..19
        }

        private val forbiddenRequestHeaders = setOf(
            "host", "connection", "content-length", "transfer-encoding", "te", "trailer", "upgrade",
            "proxy-authorization", "proxy-authenticate", "authorization", "cookie", "cookie2",
            "set-cookie", "accept-encoding", "content-encoding", "keep-alive", "expect"
        )
        private val sensitiveResponseHeaders = setOf(
            "set-cookie", "set-cookie2", "www-authenticate", "proxy-authenticate", "authorization",
            "proxy-authorization", "cookie", "location", "refresh"
        )
        private val token = Regex("[!#\$%&'*+.^_`|~0-9A-Za-z-]+")

        fun validateHeaders(headers: Map<String, String>): Map<String, String> {
            if (headers.size > 64) throw PluginHostFailure("INVALID_HEADER", "HTTP 请求头过多")
            val seen = mutableSetOf<String>()
            headers.forEach { (name, value) ->
                val lower = name.lowercase(Locale.ROOT)
                if (!token.matches(name) || lower in forbiddenRequestHeaders ||
                    lower.contains("api-key") || lower.contains("auth") || lower.contains("token") ||
                    lower.contains("secret") || !seen.add(lower) ||
                    value.length > 8192 || value.any { it.code < 32 && it != '\t' || it.code == 127 })
                    throw PluginHostFailure("INVALID_HEADER", "HTTP 请求头无效")
            }
            return headers
        }

        fun validateCredentialHeader(name: String): String {
            if (!token.matches(name) || name.lowercase(Locale.ROOT) in
                (forbiddenRequestHeaders - "authorization"))
                throw PluginHostFailure("INVALID_HEADER", "凭据请求头无效")
            return name
        }

        private fun redact(value: String, secret: String?): String =
            if (secret.isNullOrEmpty()) value else value.replace(secret, "[redacted]")

        /** Conservative global-unicast policy; special-use ranges are denied even if routable. */
        internal fun isPublicAddress(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress) return false
            val bytes = address.address.map { it.toInt() and 255 }
            if (address is Inet4Address) {
                val a = bytes[0]; val b = bytes[1]; val c = bytes[2]
                return !(a == 0 || a == 10 || a == 127 || a >= 224 ||
                    a == 100 && b in 64..127 || a == 169 && b == 254 ||
                    a == 172 && b in 16..31 || a == 192 && b == 168 ||
                    a == 192 && b == 0 && c == 0 || a == 192 && b == 0 && c == 2 ||
                    a == 192 && b == 88 && c == 99 || a == 198 && b in 18..19 ||
                    a == 198 && b == 51 && c == 100 || a == 203 && b == 0 && c == 113)
            }
            if (address is Inet6Address) {
                // 2000::/3 only, excluding IETF special-use 2001::/23, documentation and 6to4.
                return bytes[0] in 0x20..0x3f &&
                    !(bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] <= 0x01) &&
                    !(bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && bytes[3] == 0xb8) &&
                    !(bytes[0] == 0x20 && bytes[1] == 0x02)
            }
            return false
        }
    }
}
