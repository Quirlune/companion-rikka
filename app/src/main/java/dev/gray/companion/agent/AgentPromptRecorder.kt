package dev.gray.companion.agent

import dev.gray.companion.core.ChatMessage
import dev.gray.companion.core.ModelDelta
import dev.gray.companion.core.ModelGateway
import dev.gray.companion.core.ModelProfile
import dev.gray.companion.core.ModelResponse
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Process-local view of messages handed to ModelGateway, without profiles, headers or credentials. */
class AgentPromptRecorder {
    private data class Message(val role: String, val text: String, val originalChars: Int, val truncated: Boolean)
    private data class Record(val sequence: Long, val sessionId: String, val at: Long,
        val profileId: String, val model: String, val messages: List<Message>, val truncated: Boolean)

    private val records = ArrayDeque<Record>()
    private var sequence = 0L

    fun wrap(sessionId: String, delegate: ModelGateway): ModelGateway = object : ModelGateway {
        override fun maxParallelCandidates(profile: ModelProfile): Int = delegate.maxParallelCandidates(profile)

        override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
            capture(sessionId, profile, messages)
            return delegate.generate(profile, messages, onDelta)
        }

        override suspend fun generateAttempt(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
            capture(sessionId, profile, messages)
            return delegate.generateAttempt(profile, messages, onDelta)
        }

        override suspend fun generateResponseAttempt(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (ModelDelta) -> Unit): ModelResponse {
            capture(sessionId, profile, messages)
            return delegate.generateResponseAttempt(profile, messages, onDelta)
        }
    }

    fun recent(sessionId: String? = null, limit: Int = 5): JsonObject {
        val selected = synchronized(this) { records.toList() }.asReversed().asSequence()
            .filter { sessionId == null || it.sessionId == sessionId }.take(limit.coerceIn(1, 10)).toList()
        return buildJsonObject {
            put("mode", "recent")
            put("scope", "本进程交给 ModelGateway 的已编译消息；不是提供商编码后的 HTTP，也不包含旧进程记录")
            put("records", buildJsonArray { selected.forEach { record -> add(buildJsonObject {
                put("sequence", record.sequence)
                put("sessionId", record.sessionId)
                put("at", record.at)
                put("profileId", record.profileId)
                put("model", record.model)
                put("truncated", record.truncated)
                put("messages", buildJsonArray { record.messages.forEach { message -> add(buildJsonObject {
                    put("role", message.role)
                    put("text", message.text)
                    put("originalChars", message.originalChars)
                    put("truncated", message.truncated)
                }) } })
            }) } })
        }
    }

    private fun capture(sessionId: String, profile: ModelProfile, messages: List<ChatMessage>) {
        var remaining = MAX_CHARS_PER_RECORD
        var truncated = messages.size > MAX_MESSAGES_PER_RECORD
        val projected = messages.take(MAX_MESSAGES_PER_RECORD).map { message ->
            val text = message.text.take(remaining)
            remaining -= text.length
            val clipped = text.length != message.text.length
            truncated = truncated || clipped
            Message(message.role.name, text, message.text.length, clipped)
        }
        synchronized(this) {
            records.addLast(Record(++sequence, sessionId, System.currentTimeMillis(), profile.id, profile.model,
                projected, truncated))
            while (records.size > MAX_RECORDS) records.removeFirst()
        }
    }

    companion object {
        private const val MAX_RECORDS = 32
        private const val MAX_MESSAGES_PER_RECORD = 128
        private const val MAX_CHARS_PER_RECORD = 128 * 1024
    }
}
