package dev.gray.companion.core

interface ModelGateway {
    suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String
    /** One request. Report transport failures as ModelRequestFailure so core can apply its retry budget. */
    suspend fun generateAttempt(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String =
        generate(profile, messages, onDelta)
    suspend fun generateResponseAttempt(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (ModelDelta) -> Unit): ModelResponse =
        ModelResponse(generateAttempt(profile, messages) { onDelta(ModelDelta.Text(it)) })
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
    /** Decoding upgraded stored records; the admission owner saves only after full validation. */
    val requiresUpgradeSave: Boolean get() = false
    suspend fun load(): Library
    suspend fun save(library: Library)
}

/** Secrets have stable resource owners; slot namespaces belong to the storage adapter. */
enum class CredentialKind { API_KEY, PRIVATE_KEY, MODEL_API_KEY, MODEL_PRIVATE_KEY }
data class CredentialSlot(val ownerId: String, val kind: CredentialKind = CredentialKind.API_KEY)

interface CredentialStore {
    fun get(profileId: String): String
    fun put(profileId: String, secret: String)
    fun remove(profileId: String)

    fun get(slot: CredentialSlot): String {
        if (slot.kind != CredentialKind.API_KEY) throw UnsupportedOperationException("Credential slot kind is not supported")
        return get(slot.ownerId)
    }

    fun put(slot: CredentialSlot, secret: String) {
        if (slot.kind != CredentialKind.API_KEY) throw UnsupportedOperationException("Credential slot kind is not supported")
        put(slot.ownerId, secret)
    }

    fun remove(slot: CredentialSlot) {
        if (slot.kind != CredentialKind.API_KEY) throw UnsupportedOperationException("Credential slot kind is not supported")
        remove(slot.ownerId)
    }
}
