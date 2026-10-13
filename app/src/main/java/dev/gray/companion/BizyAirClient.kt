package dev.gray.companion

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.serialization.json.*

data class BizyAirGeneratedImage(val localPath: String, val width: Int, val height: Int)
enum class BizyAirPhase { QUEUED, RUNNING, DOWNLOADING }

interface IllustrationService {
    suspend fun generate(
        payload: JsonObject, key: String, onPhase: (BizyAirPhase) -> Unit = {}
    ): BizyAirGeneratedImage
}

class BizyAirFailure(
    val code: String, message: String,
    val requestId: String? = null, val uncertainDelivery: Boolean = false
) : IllegalStateException(message)

/** Built-in native integration for BizyAir OpenAPI. Each call submits exactly one task. */
class BizyAirClient(
    private val imageDirectory: File,
    private val transport: PluginHttpTransport = PluginHttpTransport(setOf("api.bizyair.ai")),
    private val pollDelayMs: Long = 2_000L
) : IllustrationService {
    override suspend fun generate(
        payload: JsonObject, key: String, onPhase: (BizyAirPhase) -> Unit
    ): BizyAirGeneratedImage {
        if (key.isBlank() || key.any { it.code !in 33..126 })
            throw BizyAirFailure("CREDENTIAL_UNAVAILABLE", "BizyAir 密钥未配置")
        val created = try {
            api("POST", "$BASE/create", key, payload.toString(), 120_000)
        } catch (failure: PluginHostFailure) {
            if (failure.delivery == "not_sent")
                throw BizyAirFailure("CREATE_NOT_SENT", "任务未提交，请检查网络连接")
            if (failure.code == "REMOTE_REJECTED")
                throw BizyAirFailure("CREATE_REJECTED", failure.message ?: "BizyAir 拒绝创建任务")
            throw BizyAirFailure("CREATE_UNCERTAIN", "提交状态不确定；远端任务可能已创建，请勿直接重试", uncertainDelivery = true)
        }
        val requestId = (created["request_id"] ?: (created["data"] as? JsonObject)?.get("request_id"))
            ?.jsonPrimitive?.contentOrNull?.takeIf { REQUEST_ID.matches(it) }
            ?: throw BizyAirFailure("CREATE_UNCERTAIN", "创建响应缺少任务编号；请勿直接重试", uncertainDelivery = true)
        onPhase(BizyAirPhase.QUEUED)
        val deadline = System.nanoTime() + MAX_POLL_NANOS
        while (true) {
            if (System.nanoTime() >= deadline)
                throw BizyAirFailure("TASK_TIMEOUT", "任务等待超时；远端任务可能仍在运行", requestId,
                    uncertainDelivery = true)
            val task = try { safeGet("$BASE/$requestId", key) }
                catch (failure: PluginHostFailure) {
                    throw BizyAirFailure("TASK_STATUS_UNAVAILABLE", "任务状态不明；请先在 BizyAir 核对", requestId,
                        uncertainDelivery = true)
                }
            val data = task["data"] as? JsonObject
                ?: throw BizyAirFailure("TASK_STATUS_INVALID", "任务状态格式无效；请先在 BizyAir 核对", requestId,
                    uncertainDelivery = true)
            when (data["status"]?.jsonPrimitive?.contentOrNull) {
                "Success" -> break
                "Failed", "Canceled" -> throw BizyAirFailure("TASK_FAILED", "BizyAir 任务未完成", requestId)
                "Running" -> onPhase(BizyAirPhase.RUNNING)
                else -> onPhase(BizyAirPhase.QUEUED)
            }
            delay(pollDelayMs)
        }
        val outputs = try { safeGet("$BASE/$requestId/outputs", key) }
            catch (failure: PluginHostFailure) {
                throw BizyAirFailure("OUTPUT_UNAVAILABLE", "图像结果暂不可用", requestId)
            }
        val rows = outputs["data"]?.jsonObject?.get("outputs") as? JsonArray
            ?: throw BizyAirFailure("OUTPUT_INVALID", "图像结果格式无效", requestId)
        val imageUrl = rows.asSequence().mapNotNull { row ->
            (row as? JsonObject)?.get("object_url")?.jsonPrimitive?.contentOrNull
        }.firstOrNull() ?: throw BizyAirFailure("OUTPUT_MISSING", "任务未返回图像", requestId)
        onPhase(BizyAirPhase.DOWNLOADING)
        return downloadImage(imageUrl, requestId)
    }

    private suspend fun api(method: String, url: String, key: String, body: String?, timeoutMs: Long): JsonObject {
        val response = transport.execute(PluginTransportRequest(
            url, method,
            mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json") +
                (if (method == "POST") mapOf("X-Bizyair-Task-Async" to "enable") else emptyMap()),
            body, timeoutMs, 1_048_576, key
        ))
        if (response.status !in 200..299) {
            if (method == "POST" && response.status in setOf(400, 401, 403, 404, 422, 429))
                throw PluginHostFailure("REMOTE_REJECTED", when (response.status) {
                    401, 403 -> "BizyAir 密钥无效或无权限（HTTP ${response.status}）"
                    429 -> "BizyAir 当前繁忙或额度受限（HTTP 429）"
                    400, 422 -> "BizyAir 拒绝请求参数（HTTP ${response.status}）"
                    else -> "BizyAir 拒绝创建任务（HTTP ${response.status}）"
                }, delivery = "received")
            throw PluginHostFailure("NETWORK_ERROR", "BizyAir HTTP ${response.status}", delivery =
                if (method == "POST") "unknown" else "received")
        }
        val parsed = try { Json.parseToJsonElement(response.body).jsonObject }
            catch (_: Exception) { throw PluginHostFailure("INVALID_RESPONSE", "BizyAir 响应格式无效", delivery = "received") }
        val remoteCode = parsed["code"]?.jsonPrimitive?.intOrNull
        if (method == "POST" && remoteCode != null && remoteCode != 20000)
            throw PluginHostFailure("REMOTE_REJECTED", "BizyAir 拒绝创建任务（业务码 $remoteCode）", delivery = "received")
        if (method != "POST" && remoteCode != 20000)
            throw PluginHostFailure("REMOTE_ERROR", "BizyAir 任务响应失败", delivery = "received")
        return parsed
    }

    /** Only idempotent status/output reads are retried. A create POST is never replayed. */
    private suspend fun safeGet(url: String, key: String): JsonObject {
        var last: PluginHostFailure? = null
        repeat(3) { attempt ->
            try { return api("GET", url, key, null, 30_000) }
            catch (failure: PluginHostFailure) {
                last = failure
                if (attempt < 2) delay(1_000L * (attempt + 1))
            }
        }
        throw last ?: PluginHostFailure("NETWORK_ERROR", "BizyAir 状态读取失败")
    }

    private suspend fun downloadImage(rawUrl: String, requestId: String): BizyAirGeneratedImage {
        val url = rawUrl.toHttpUrlOrNull()
        if (url == null || url.scheme != "https" || url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || rawUrl.contains('#') || rawUrl.contains('\\'))
            throw BizyAirFailure("OUTPUT_URL_DENIED", "图像地址不安全", requestId)
        val addresses = try { withContext(Dispatchers.IO) { InetAddress.getAllByName(url.host).toList() } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { throw BizyAirFailure("OUTPUT_UNAVAILABLE", "图像地址解析失败", requestId) }
        if (addresses.isEmpty() || addresses.any { !PluginHttpTransport.isPublicAddress(it) &&
                !(url.host == "storage.bizyair.ai" && PluginHttpTransport.isProxyFakeAddress(it)) })
            throw BizyAirFailure("OUTPUT_URL_DENIED", "图像地址不安全", requestId)
        val client = OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .dns(Dns { host ->
                if (!host.equals(url.host, true)) throw java.net.UnknownHostException("Host mismatch")
                addresses
            })
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .cookieJar(CookieJar.NO_COOKIES)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
        val output = File(imageDirectory, UUID.randomUUID().toString() + ".png")
        val temp = File(imageDirectory, ".${output.name}.tmp")
        try {
            withContext(Dispatchers.IO) { if (!imageDirectory.isDirectory && !imageDirectory.mkdirs())
                throw BizyAirFailure("STORAGE_UNAVAILABLE", "无法保存插图", requestId) }
            val call = client.newCall(Request.Builder().url(url).get().build())
            val dims = try {
                suspendCancellableCoroutine<Pair<Int, Int>> { continuation ->
                    continuation.invokeOnCancellation { call.cancel(); temp.delete() }
                    call.enqueue(object : Callback {
                        override fun onFailure(call: Call, e: IOException) {
                            temp.delete()
                            if (continuation.isActive) continuation.resumeWithException(
                                BizyAirFailure("OUTPUT_UNAVAILABLE", "图像下载失败", requestId))
                        }
                        override fun onResponse(call: Call, response: Response) {
                            try {
                                val dimensions = response.use { result ->
                                    if (result.code != 200)
                                        throw BizyAirFailure("OUTPUT_UNAVAILABLE", "图像下载返回 HTTP ${result.code}", requestId)
                                    temp.outputStream().use { sink ->
                                        val stream = result.body?.byteStream()
                                            ?: throw BizyAirFailure("OUTPUT_INVALID", "图像内容为空", requestId)
                                        val bytes = ByteArray(8192)
                                        var countTotal = 0L
                                        while (true) {
                                            val count = stream.read(bytes)
                                            if (count < 0) break
                                            countTotal += count
                                            if (countTotal > MAX_IMAGE_BYTES)
                                                throw BizyAirFailure("OUTPUT_TOO_LARGE", "图像超过大小上限", requestId)
                                            sink.write(bytes, 0, count)
                                        }
                                    }
                                    pngDimensions(temp)
                                        ?: throw BizyAirFailure("OUTPUT_INVALID", "图像不是有效 PNG", requestId)
                                }
                                if (continuation.isActive) continuation.resume(dimensions)
                            } catch (failure: BizyAirFailure) {
                                temp.delete()
                                if (continuation.isActive) continuation.resumeWithException(failure)
                            } catch (_: Exception) {
                                temp.delete()
                                if (continuation.isActive) continuation.resumeWithException(
                                    BizyAirFailure("OUTPUT_UNAVAILABLE", "图像下载失败", requestId))
                            }
                        }
                    })
                }
            } catch (cancelled: CancellationException) { call.cancel(); throw cancelled }
            if (!temp.renameTo(output)) throw BizyAirFailure("STORAGE_UNAVAILABLE", "无法保存插图", requestId)
            return BizyAirGeneratedImage(output.absolutePath, dims.first, dims.second)
        } finally {
            temp.delete()
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdown()
            }
        }
    }

    companion object {
        private const val BASE = "https://api.bizyair.ai/v1/webapp/task/openapi"
        private const val MAX_IMAGE_BYTES = 20L * 1024 * 1024
        private const val MAX_POLL_NANOS = 20L * 60 * 1_000_000_000
        private val REQUEST_ID = Regex("[A-Za-z0-9-]{1,100}")
        private val PNG_HEADER = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)

        internal fun pngDimensions(file: File): Pair<Int, Int>? {
            if (file.length() < 33) return null
            return java.io.RandomAccessFile(file, "r").use { source ->
                val head = ByteArray(24)
                source.readFully(head)
                if (!head.copyOfRange(0, 8).contentEquals(PNG_HEADER) ||
                    !head.copyOfRange(8, 12).contentEquals(byteArrayOf(0, 0, 0, 13)) ||
                    !head.copyOfRange(12, 16).contentEquals("IHDR".toByteArray(Charsets.US_ASCII))) return@use null
                val width = java.nio.ByteBuffer.wrap(head, 16, 4).int
                val height = java.nio.ByteBuffer.wrap(head, 20, 4).int
                val tail = ByteArray(12)
                source.seek(file.length() - 12)
                source.readFully(tail)
                val iend = byteArrayOf(0, 0, 0, 0, 73, 69, 78, 68, -82, 66, 96, -126)
                if (width !in 1..16_384 || height !in 1..16_384 || !tail.contentEquals(iend))
                    null else width to height
            }
        }
    }
}
