package me.rerere.prompt

import kotlinx.serialization.Serializable

/** Names are presentation; these identifiers are the persisted addressing protocol. */
@Serializable
data class ReferenceId(val provider: String, val name: String) {
    init {
        require(provider.isNotBlank() && name.isNotBlank())
    }
}

@Serializable
data class ContextScope(val assistantId: String, val conversationId: String, val branchId: String) {
    init {
        require(listOf(assistantId, conversationId, branchId).all(String::isNotBlank))
    }
}

@Serializable
data class SourceVersion(val id: String, val revision: String) {
    init {
        require(id.isNotBlank() && revision.isNotBlank()) {
            "Source ID and revision must be nonempty"
        }
    }
}

@Serializable
data class ProviderState(val id: String, val enabled: Boolean = true, val epoch: Long = 0)

@Serializable
data class ReferenceDefinition(
    val id: ReferenceId,
    val title: String,
    val staticText: String? = null,
)

@Serializable
data class PromptPackage(val id: String, val version: Int, val entries: List<ReferenceDefinition>) {
    init {
        require(id.isNotBlank() && version > 0)
        require(entries.all { it.id.provider == id && it.staticText != null })
        require(entries.map { it.id }.distinct().size == entries.size)
    }
}

/** Issued before execution. A later ticket supersedes an earlier in-flight publication. */
@Serializable
data class PublicationTicket(
    val id: String,
    val reference: ReferenceId,
    val scope: ContextScope,
    val sequence: Long,
    val providerEpoch: Long,
    val sources: List<SourceVersion>,
    val eventOrder: Long = 0,
)

@Serializable
data class Artifact(
    val ticket: PublicationTicket,
    val text: String,
    val createdAtMillis: Long,
    /** Exact input artifacts; separate from the source-message coordinates. */
    val dependencies: List<String> = emptyList(),
)

@Serializable
enum class ReferenceStatus {
    READY,
    UNREGISTERED,
    DISABLED,
    EMPTY,
    STALE,
}

data class ResolvedReference(
    val id: ReferenceId,
    val status: ReferenceStatus,
    val artifact: Artifact? = null,
    val text: String? = artifact?.text,
)

/** Exact source revisions must still occur in this branch; appended messages are allowed. */
fun Artifact.isApplicableTo(sources: List<SourceVersion>): Boolean {
    val positions = sources.withIndex().associate { it.value to it.index }
    var previous = -1
    return ticket.sources.all { source ->
        val position = positions[source] ?: return@all false
        (position > previous).also { previous = position }
    }
}
