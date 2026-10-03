package dev.gray.companion.core

interface ModelGateway {
    suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String
    suspend fun generateAttempt(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String =
        generate(profile, messages, onDelta)
    fun maxParallelCandidates(profile: ModelProfile): Int = 1
}

/** Transport advice for the bounded model-candidate loop. */
open class ModelRequestFailure(
    message: String,
    val retryable: Boolean = true,
    val serialFallback: Boolean = false,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

interface LibraryRepository {
    suspend fun load(): Library
    suspend fun save(library: Library)
}

interface CredentialStore {
    fun get(profileId: String): String
    fun put(profileId: String, secret: String)
    fun remove(profileId: String)
}
