package dev.gray.companion

import dev.gray.companion.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import java.net.URI
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/** Device grants and secrets are deliberately separate from the captured plugin resource. */
interface PluginHostAccess {
    fun isGranted(context: PluginExecutionContext): Boolean
    fun credential(pluginId: String, alias: String): String
}

/** A single invocation's capability registry and budget. Never reuse this instance across runs. */
class PluginHostRuntime(
    private val context: PluginExecutionContext,
    private val access: PluginHostAccess,
    private val transport: PluginHttpTransport = PluginHttpTransport()
) {
    private val startedNanos = System.nanoTime()
    private val httpCount = AtomicInteger(0)
    private val handlers: Map<String, suspend (JsonObject) -> JsonElement> = mapOf(
        PluginCapabilities.NETWORK_HTTP to { network(it) },
        PluginCapabilities.RUNTIME_SLEEP to { sleep(it) },
        PluginCapabilities.CAPABILITIES_DESCRIBE to {
            if (it.isNotEmpty()) throw PluginHostFailure("INVALID_ARGUMENT", "此能力不接受参数")
            PluginCapabilities.manifest()
        }
    )

    suspend fun invoke(capability: String, args: JsonObject): JsonObject {
        return try {
            val remaining = remainingMillis()
            if (remaining <= 0) throw PluginHostFailure("RUN_TIMEOUT", "插件运行超时")
            val handler = handlers[capability] ?: throw PluginHostFailure("UNKNOWN_CAPABILITY", "未知宿主能力")
            val data = handler(args)
            buildJsonObject { put("ok", true); put("data", data) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: PluginHostFailure) {
            failureEnvelope(failure)
        } catch (_: Exception) {
            failureEnvelope(PluginHostFailure("HOST_ERROR", "宿主调用失败", delivery = "unknown"))
        }
    }

    /** String-only QuickJS boundary; malformed inputs produce structured errors without exposing arguments. */
    suspend fun invokeJson(raw: String): String {
        val envelope = try {
            val value = LibraryFormat.json.parseToJsonElement(raw).jsonObject
            if (value.keys.any { it !in setOf("capability", "args") })
                throw PluginHostFailure("INVALID_ARGUMENT", "宿主参数无效")
            val capabilityValue = value["capability"] as? JsonPrimitive
                ?: throw PluginHostFailure("INVALID_ARGUMENT", "缺少宿主能力名称")
            if (!capabilityValue.isString) throw PluginHostFailure("INVALID_ARGUMENT", "宿主能力名称须为字符串")
            val capability = capabilityValue.content
            val args = when (val rawArgs = value["args"]) {
                null -> JsonObject(emptyMap())
                is JsonObject -> rawArgs
                else -> throw PluginHostFailure("INVALID_ARGUMENT", "宿主参数须为对象")
            }
            invoke(capability, args)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: PluginHostFailure) {
            failureEnvelope(failure)
        } catch (_: Exception) {
            failureEnvelope(PluginHostFailure("INVALID_ARGUMENT", "宿主参数无效"))
        }
        return envelope.toString()
    }

    private suspend fun sleep(args: JsonObject): JsonElement {
        if (args.keys != setOf("milliseconds"))
            throw PluginHostFailure("INVALID_ARGUMENT", "等待参数无效")
        val millis = args["milliseconds"]?.jsonPrimitive?.longOrNull
            ?: throw PluginHostFailure("INVALID_ARGUMENT", "milliseconds 须为整数")
        if (millis !in 0..10_000 || millis > remainingMillis())
            throw PluginHostFailure("INVALID_ARGUMENT", "等待时间超出限额")
        delay(millis)
        return JsonObject(emptyMap())
    }

    private suspend fun network(args: JsonObject): JsonElement {
        val request = try { LibraryFormat.json.decodeFromJsonElement(PluginHttpRequest.serializer(), args) }
            catch (_: SerializationException) { throw PluginHostFailure("INVALID_ARGUMENT", "HTTP 参数无效") }
        if (httpCount.incrementAndGet() > PluginCapabilities.MAX_HTTP_REQUESTS)
            throw PluginHostFailure("REQUEST_LIMIT", "HTTP 请求次数超出限额")
        val uri = try { URI(request.url) } catch (_: Exception) { null }
            ?: throw PluginHostFailure("INVALID_URL", "仅允许规范 HTTPS URL")
        val host = uri.host ?: throw PluginHostFailure("INVALID_URL", "仅允许规范 HTTPS URL")
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.rawFragment != null || uri.port == 0 ||
            uri.port !in -1..65535 || host.endsWith('.') || request.url.any { it <= ' ' })
            throw PluginHostFailure("INVALID_URL", "仅允许规范 HTTPS URL")
        val origin = try { URI("https", null, host.lowercase(Locale.ROOT), uri.port, null, null, null).toASCIIString() }
            catch (_: Exception) { throw PluginHostFailure("INVALID_URL", "仅允许规范 HTTPS URL") }
        if (request.method !in PluginCapabilities.httpMethods)
            throw PluginHostFailure("INVALID_ARGUMENT", "HTTP 方法不受支持")
        if (request.timeoutMs !in 1..PluginCapabilities.MAX_HTTP_TIMEOUT_MS ||
            request.maxResponseBytes !in 1..PluginCapabilities.MAX_RESPONSE_BYTES)
            throw PluginHostFailure("INVALID_ARGUMENT", "HTTP 限额无效")
        if ((request.body?.toByteArray(Charsets.UTF_8)?.size ?: 0) > PluginCapabilities.MAX_REQUEST_BYTES)
            throw PluginHostFailure("REQUEST_TOO_LARGE", "HTTP 请求体超出限额")
        if (request.method in setOf("GET", "HEAD") && request.body != null)
            throw PluginHostFailure("INVALID_ARGUMENT", "此 HTTP 方法不接受请求体")
        val alias = request.credential?.alias
        val permitted = context.code.permissions.any { rule ->
            rule.capability == PluginCapabilities.NETWORK_HTTP && origin in rule.origins &&
                request.method in rule.methods && (alias == null || alias in rule.credentials)
        }
        if (!permitted || !access.isGranted(context))
            throw PluginHostFailure("PERMISSION_DENIED", "插件网络权限未授予或超出声明范围")
        val headers = PluginHttpTransport.validateHeaders(request.headers).toMutableMap()
        val secret = request.credential?.let { credential ->
            if (!PluginCapabilities.validAlias(credential.alias))
                throw PluginHostFailure("INVALID_ARGUMENT", "凭据别名无效")
            val name = PluginHttpTransport.validateCredentialHeader(credential.header)
            if (headers.keys.any { it.equals(name, true) } || credential.prefix.any { it == '\r' || it == '\n' || it.code < 32 })
                throw PluginHostFailure("INVALID_HEADER", "凭据请求头无效")
            val value = try { access.credential(context.pluginId, credential.alias) }
                catch (_: Exception) { throw PluginHostFailure("CREDENTIAL_UNAVAILABLE", "凭据未配置") }
            if (value.isBlank() || (credential.prefix + value).any { it.code !in 32..126 })
                throw PluginHostFailure("CREDENTIAL_UNAVAILABLE", "凭据未配置")
            headers[name] = credential.prefix + value
            value
        }
        val result = transport.execute(PluginTransportRequest(
            request.url, request.method, headers, request.body,
            minOf(request.timeoutMs, remainingMillis().coerceAtLeast(1)), request.maxResponseBytes, secret
        ))
        return buildJsonObject {
            put("status", result.status)
            put("headers", buildJsonObject { result.headers.forEach { (name, value) -> put(name, value) } })
            put("body", result.body)
        }
    }

    private fun remainingMillis(): Long = PluginCapabilities.MAX_TOTAL_RUNTIME_MS -
        (System.nanoTime() - startedNanos) / 1_000_000

    private fun failureEnvelope(failure: PluginHostFailure): JsonObject = buildJsonObject {
        put("ok", false)
        put("error", buildJsonObject {
            put("code", failure.code)
            put("message", failure.message ?: "宿主调用失败")
            put("retryable", failure.retryable)
            put("delivery", failure.delivery)
        })
    }
}

class PluginHostFailure(
    val code: String, override val message: String,
    val retryable: Boolean = false, val delivery: String = "not_sent"
) : Exception(message)
