package dev.gray.companion.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import java.io.EOFException
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class AgentResponse(
    val output: List<JsonObject>,
    val text: String,
    val inputTokens: Long,
    val outputTokens: Long,
)

/** A code and request ID are safe diagnostics; response bodies and bearer tokens are never exposed. */
class AgentResponseException(
    val status: Int? = null,
    val errorCode: String? = null,
    val requestId: String? = null,
    message: String,
) : IOException(message)

class AgentResponsesClient(private val tokenProvider: suspend (Boolean) -> String) {
    private val codec = Json { ignoreUnknownKeys = true }
    private val http = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(5, TimeUnit.MINUTES)
        .callTimeout(0, TimeUnit.SECONDS).build()

    suspend fun models(): List<AgentModel> {
        val token = tokenProvider(false)
        val request = Request.Builder().url("https://api.openai.com/v1/models")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json").get().build()
        return executeCancellable(request) { response, _ ->
                if (!response.isSuccessful) throw httpError(response.code,
                    response.header("x-request-id") ?: response.header("openai-request-id"),
                    response.body?.byteStream()?.let { readLimited(it, 8_192) })
                val body = response.body?.byteStream()?.let { readLimited(it, 512_000) }
                    ?: throw AgentResponseException(message = "Model catalog is unavailable")
                val document = try { codec.parseToJsonElement(body).jsonObject }
                    catch (_: Exception) { throw AgentResponseException(message = "Model catalog is invalid") }
                val listed = document["models"] as? JsonArray
                val entries = listed ?: document["data"] as? JsonArray
                    ?: throw AgentResponseException(message = "Model catalog is invalid")
                entries.mapNotNull { it as? JsonObject }.mapNotNull { entry ->
                    if (listed != null && entry.value("visibility") != "list") return@mapNotNull null
                    val slug = if (listed != null) entry.value("slug") else entry.value("slug") ?: entry.value("id")
                    slug?.takeIf { it.isNotBlank() }?.let {
                        AgentModel(it, entry.value("display_name")?.takeIf(String::isNotBlank) ?: it)
                    }
                }
        }
    }

    suspend fun respond(
        model: String,
        effort: String,
        instructions: String,
        input: List<JsonObject>,
        onText: (String) -> Unit,
    ): AgentResponse {
        val token = tokenProvider(false)
        val body = requestBody(model, effort, instructions, input).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url("https://api.openai.com/v1/responses")
            .header("Authorization", "Bearer $token")
            .header("Accept", "text/event-stream")
            .post(body).build()
        return executeCancellable(request) { response, stillActive ->
                if (!response.isSuccessful) throw httpError(response.code,
                    response.header("x-request-id") ?: response.header("openai-request-id"),
                    response.body?.byteStream()?.let { readLimited(it, 8_192) })
                val stream = response.body?.source()
                    ?: throw AgentResponseException(message = "ChatGPT stream did not open")
                parseResponseStream(stream) { delta ->
                    if (!stillActive()) throw CancellationException("Agent request canceled")
                    onText(delta)
                }
        }
    }

    /** OkHttp cancellation closes both an in-flight connection and an active SSE read. */
    private suspend fun <T> executeCancellable(
        request: Request, consume: (Response, () -> Boolean) -> T,
    ): T = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        try {
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            if (!continuation.isActive) return
                            val value = consume(it) { continuation.isActive }
                            if (continuation.isActive) continuation.resume(value)
                        } catch (e: Exception) {
                            if (continuation.isActive) continuation.resumeWithException(e)
                        }
                    }
                }
            })
        } catch (e: Exception) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
    }

    internal fun requestBody(model: String, effort: String, instructions: String,
                             input: List<JsonObject>): JsonObject = buildJsonObject {
        put("model", model)
        put("instructions", instructions)
        put("input", JsonArray(input))
        put("store", false)
        put("stream", true)
        if (effort.isNotBlank()) put("reasoning", buildJsonObject { put("effort", effort) })
        put("include", buildJsonArray { add(JsonPrimitive("reasoning.encrypted_content")) })
        put("tools", buildJsonArray {
            add(buildJsonObject {
                put("type", "namespace")
                put("name", "velune")
                put("description", "Local Agent resource and workspace operations controlled by velune.")
                put("tools", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "function")
                        put("name", "invoke")
                        put("description", "Invoke one available velune operation using JSON arguments.")
                        put("strict", true)
                        put("parameters", buildJsonObject {
                            put("type", "object")
                            put("properties", buildJsonObject {
                                put("operation", buildJsonObject { put("type", "string") })
                                put("arguments", buildJsonObject { put("type", "string") })
                            })
                            put("required", buildJsonArray {
                                add(JsonPrimitive("operation")); add(JsonPrimitive("arguments"))
                            })
                            put("additionalProperties", false)
                        })
                    })
                })
            })
        })
    }

    private fun httpError(status: Int, requestId: String?, body: String?): AgentResponseException {
        val code = try {
            val obj = body?.let { codec.parseToJsonElement(it).jsonObject }
            ((obj?.get("error") as? JsonObject)?.value("code"))?.takeIf { SAFE_CODE.matches(it) }
        } catch (_: Exception) { null }
        val message = when {
            status == 401 -> "ChatGPT sign-in is no longer authorized; connect again"
            code == "subscription_sharing_usage_limit_exceeded" -> "ChatGPT usage limit reached; manage usage in ChatGPT settings"
            status == 429 -> "ChatGPT usage is temporarily limited"
            status == 403 -> "ChatGPT plan use is unavailable for this request"
            status >= 500 -> "ChatGPT service is temporarily unavailable"
            else -> "ChatGPT request failed"
        }
        return AgentResponseException(status, code, requestId, message)
    }

    private fun readLimited(stream: java.io.InputStream, max: Int): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4_096)
        while (true) {
            val n = stream.read(buffer, 0, minOf(buffer.size, max + 1 - output.size()))
            if (n < 0) break
            output.write(buffer, 0, n)
            if (output.size() > max) throw AgentResponseException(message = "ChatGPT response is too large")
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private companion object {
        val SAFE_CODE = Regex("[a-z0-9_]{1,96}")
    }
}

/** Parses until the authoritative completed event. Partial streams never become successes. */
internal fun parseResponseStream(source: BufferedSource, onText: (String) -> Unit): AgentResponse {
    val textDeltas = StringBuilder()
    var eventName: String? = null
    val data = StringBuilder()
    while (true) {
        val line = try { source.readUtf8LineStrict(1_048_576) }
            catch (_: EOFException) { throw AgentResponseException(message = "ChatGPT stream ended before completion") }
        if (line.isEmpty()) {
            if (data.isNotEmpty()) {
                val payload = data.toString().removeSuffix("\n")
                if (payload == "[DONE]") throw AgentResponseException(message = "ChatGPT stream ended before completion")
                val event = try { Json.parseToJsonElement(payload).jsonObject }
                    catch (_: Exception) { throw AgentResponseException(message = "ChatGPT stream is invalid") }
                when (event.value("type") ?: eventName) {
                    "response.output_text.delta" -> {
                        val delta = event.value("delta").orEmpty()
                        textDeltas.append(delta)
                        onText(delta)
                    }
                    "response.completed" -> {
                        val response = event["response"] as? JsonObject
                            ?: throw AgentResponseException(message = "ChatGPT completion is invalid")
                        val output = (response["output"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                            ?: throw AgentResponseException(message = "ChatGPT completion has no output")
                        val finalText = output.flatMap { item ->
                            (item["content"] as? JsonArray)?.mapNotNull { part ->
                                (part as? JsonObject)?.takeIf { it.value("type") == "output_text" }?.value("text")
                            }.orEmpty()
                        }.joinToString("").ifEmpty { textDeltas.toString() }
                        val usage = response["usage"] as? JsonObject
                        return AgentResponse(output, finalText,
                            usage?.value("input_tokens")?.toLongOrNull() ?: 0,
                            usage?.value("output_tokens")?.toLongOrNull() ?: 0)
                    }
                    "response.failed" -> {
                        val response = event["response"] as? JsonObject
                        val error = response?.get("error") as? JsonObject
                        val code = error?.value("code")?.takeIf { Regex("[a-z0-9_]{1,96}").matches(it) }
                        throw AgentResponseException(errorCode = code,
                            message = if (code == "subscription_sharing_usage_limit_exceeded")
                                "ChatGPT usage limit reached; manage usage in ChatGPT settings"
                            else "ChatGPT response failed")
                    }
                    "response.incomplete" -> throw AgentResponseException(message = "ChatGPT response was incomplete")
                    "error" -> throw AgentResponseException(message = "ChatGPT stream failed")
                }
            }
            eventName = null
            data.setLength(0)
            continue
        }
        if (line.startsWith("event:")) eventName = line.substringAfter(':').trim()
        else if (line.startsWith("data:")) {
            data.append(line.substringAfter(':').trimStart()).append('\n')
            if (data.length > 2_097_152) throw AgentResponseException(message = "ChatGPT event is too large")
        }
    }
}

private fun JsonObject.value(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
