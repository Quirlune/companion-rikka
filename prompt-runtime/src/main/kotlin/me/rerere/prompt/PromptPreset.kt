package me.rerere.prompt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class PromptRole {
    SYSTEM,
    USER,
    ASSISTANT,
}

@Serializable
sealed interface PromptSource {
    @Serializable @SerialName("literal") data class Literal(val text: String) : PromptSource

    @Serializable @SerialName("reference") data class Reference(val id: ReferenceId) : PromptSource

    @Serializable @SerialName("builtin") data class Builtin(val key: String) : PromptSource

    @Serializable @SerialName("history") data object History : PromptSource
}

@Serializable
data class PromptBlock(
    val id: String,
    val source: PromptSource,
    val role: PromptRole = PromptRole.SYSTEM,
    val enabled: Boolean = true,
)

@Serializable
data class PromptPreset(
    val id: String,
    val blocks: List<PromptBlock>,
    val historyPolicy: HistoryPolicy = HistoryPolicy(),
) {
    init {
        require(id.isNotBlank())
        require(blocks.all { it.id.isNotBlank() })
        require(blocks.map { it.id }.distinct().size == blocks.size) { "Duplicate block ID" }
        require(blocks.count { it.source == PromptSource.History && it.enabled } == 1) {
            "A preset must contain exactly one enabled history block"
        }
    }

    companion object {
        const val LEGACY_SYSTEM = "assistant.system"

        fun default() =
            PromptPreset(
                "default",
                listOf(
                    PromptBlock("system", PromptSource.Builtin(LEGACY_SYSTEM)),
                    PromptBlock("history", PromptSource.History),
                ),
            )
    }
}

data class CompiledPromptBlock(
    val id: String,
    val role: PromptRole,
    val text: String?,
    val history: Boolean,
)

data class PromptSnapshot(
    val presetId: String,
    val blocks: List<CompiledPromptBlock>,
    val references: List<ResolvedReference>,
    val historyProjection: HistoryProjection? = null,
)

/** Pure compilation. References are resolved as one snapshot before this function runs. */
object PromptCompiler {
    fun compile(
        preset: PromptPreset,
        builtins: Map<String, String>,
        references: List<ResolvedReference>,
        sources: List<SourceVersion> = emptyList(),
    ): PromptSnapshot {
        val byId = references.associateBy { it.id }
        val blocks =
            preset.blocks
                .filter { it.enabled }
                .mapNotNull { block ->
                    if (block.source == PromptSource.History) {
                        return@mapNotNull CompiledPromptBlock(
                            block.id,
                            block.role,
                            null,
                            history = true,
                        )
                    }
                    val text =
                        when (val source = block.source) {
                            is PromptSource.Literal -> source.text
                            is PromptSource.Builtin ->
                                builtins[source.key] ?: error("Unknown builtin: ${source.key}")
                            is PromptSource.Reference ->
                                byId[source.id]?.takeIf { it.status == ReferenceStatus.READY }?.text
                            PromptSource.History -> error("History handled above")
                        }
                    text
                        ?.takeIf { it.isNotBlank() }
                        ?.let {
                            CompiledPromptBlock(block.id, block.role, it, history = false)
                        }
                }
        return PromptSnapshot(
            preset.id,
            blocks,
            references.toList(),
            HistoryProjector.project(
                preset.historyPolicy,
                byId[preset.historyPolicy.summary],
                sources,
            ),
        )
    }
}
