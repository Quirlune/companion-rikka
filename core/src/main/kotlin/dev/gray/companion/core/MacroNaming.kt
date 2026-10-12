package dev.gray.companion.core

import java.util.UUID

/** Editable aliases are independent of legacy runtime addresses and stable object identities. */
object MacroNaming {
    private val slugPattern = Regex("[a-z][a-z0-9_-]*")

    fun isValidSlug(slug: String): Boolean = slugPattern.matches(slug)

    fun slugProblem(slug: String, siblingSlugs: Collection<String> = emptyList()): String? = when {
        !isValidSlug(slug) -> "英文标识须以小写字母开头，只能含小写字母、数字、下划线和连字符"
        slug in siblingSlugs -> "同级英文标识已存在"
        else -> null
    }

    fun nameProblem(name: String, siblingNames: Collection<String> = emptyList()): String? = when {
        name.isBlank() -> "显示名称不能为空"
        siblingNames.any { it.trim() == name.trim() } -> "同级显示名称已存在"
        else -> null
    }

    fun toggleTag(tagIds: List<String>, tagId: String): List<String> =
        if (tagId in tagIds) tagIds.filterNot { it == tagId } else tagIds + tagId

    /** Fill only new, omitted metadata. Sessions, archives and captured environments are untouched. */
    fun normalize(library: Library): Library {
        val updated = library.copy(
            characters = siblings(library.characters, { it.slug }) { value, used -> normalize(value, used) },
            packages = siblings(library.packages, { it.slug }) { value, used -> normalize(value, used) },
            mainCalls = siblings(library.mainCalls, { it.slug }) { value, used -> normalize(value, used) },
            groups = siblings(library.groups, { it.slug }) { value, used -> normalize(value, used) },
            personas = siblings(library.personas, { it.slug }) { value, used -> normalize(value, used) },
            promptTags = siblings(library.promptTags, { it.slug }) { value, used -> normalize(value, used) }
        )
        return if (updated == library) library else updated
    }

    fun normalize(value: CharacterCard, siblingSlugs: Collection<String> = emptyList()): CharacterCard = value.copy(
        slug = alias(value.slug, value.namespace, "character", value.id, siblingSlugs),
        entries = siblings(value.entries, { it.slug }) { entry, used ->
            val basic = CharacterFields.definitions.firstOrNull { it.key == CharacterFields.canonicalKey(entry.key) }
            val titled = if (entry.title.isEmpty() && basic != null) entry.copy(title = basic.title) else entry
            normalize(titled, used)
        }
    ).retainOriginal(value)

    fun normalize(value: PromptPackage, siblingSlugs: Collection<String> = emptyList()): PromptPackage = value.copy(
        slug = alias(value.slug, value.namespace, "pack", value.id, siblingSlugs),
        entries = siblings(value.entries, { it.slug }) { entry, used -> normalize(entry, used) }
    ).retainOriginal(value)

    fun normalize(value: MainCall, siblingSlugs: Collection<String> = emptyList()): MainCall {
        // The history processor shares its owner's call namespace, including when disabled.
        val calls = siblings(value.configuredWorkflows(), { it.slug }) { call, used ->
            normalize(call, used)
        }
        return value.copy(
            slug = alias(value.slug, "", "main", value.id, siblingSlugs),
            workflows = calls.take(value.workflows.size),
            history = value.history.copy(processor = value.history.processor.copy(
                workflow = calls[value.workflows.size],
                aggregationWorkflow = calls.getOrNull(value.workflows.size + 1)))
        ).retainOriginal(value)
    }

    fun normalize(value: CallGroup, siblingSlugs: Collection<String> = emptyList()): CallGroup = value.copy(
        slug = alias(value.slug, "", "group", value.id, siblingSlugs),
        workflows = siblings(value.workflows, { it.slug }) { call, used -> normalize(call, used) }
    ).retainOriginal(value)

    fun normalize(value: Workflow, siblingSlugs: Collection<String> = emptyList()): Workflow = value.copy(
        slug = alias(value.slug, "", "call", value.id, siblingSlugs),
        nodes = siblings(value.nodes, { it.slug }) { node, used -> normalize(node, used) }
    ).retainOriginal(value)

    fun normalize(value: WorkflowNode, siblingSlugs: Collection<String> = emptyList()): WorkflowNode = value.copy(
        // Blocks are local prompt structure, not independently addressable macros.
        // Keep legacy block metadata unchanged while naming the node's result.
        slug = alias(value.slug, value.outputKey, "output", value.id, siblingSlugs)
    ).retainOriginal(value)

    /** Legacy API: prompt blocks no longer receive macro aliases. */
    @Suppress("UNUSED_PARAMETER")
    fun normalize(value: PromptBlock, siblingSlugs: Collection<String> = emptyList()): PromptBlock = value

    fun normalize(value: PromptEntry, siblingSlugs: Collection<String> = emptyList()): PromptEntry = value.copy(
        title = value.title.ifEmpty { value.key.ifBlank { "词条" } },
        slug = alias(value.slug, value.key, "entry", value.id, siblingSlugs)
    ).retainOriginal(value)

    fun normalize(value: UserPersona, siblingSlugs: Collection<String> = emptyList()): UserPersona = value.copy(
        slug = alias(value.slug, "", "persona", value.id, siblingSlugs)
    ).retainOriginal(value)

    fun normalize(value: PromptTag, siblingSlugs: Collection<String> = emptyList()): PromptTag = value.copy(
        slug = alias(value.slug, "", "tag", value.id, siblingSlugs)
    ).retainOriginal(value)

    // Recovery compares execution inputs while keeping the captured resource metadata unchanged.
    // Existing display names/titles can affect prompts and attribution, so only new metadata is removed.
    internal fun runtimeShape(value: CharacterCard): CharacterCard = value.copy(
        slug = "", boundPersonaId = null, entries = value.entries.map { runtimeShape(it) }
    )

    internal fun runtimeShape(value: PromptPackage): PromptPackage = value.copy(
        slug = "", entries = value.entries.map { runtimeShape(it) }
    )

    internal fun runtimeShape(value: UserPersona): UserPersona = value.copy(slug = "", tagIds = emptyList())

    internal fun runtimeShape(value: PromptEntry): PromptEntry = value.copy(title = "", slug = "", tagIds = emptyList())

    internal fun runtimeShape(value: MainCall): MainCall = ExtensionCommands.withoutMetadata(value).copy(
        slug = "", avatar = null, workflows = value.workflows.map { runtimeShape(it) },
        history = value.history.copy(processor = value.history.processor.copy(
            workflow = runtimeShape(value.history.processor.workflow),
            aggregationWorkflow = value.history.processor.aggregationWorkflow?.let { runtimeShape(it) }))
    )

    internal fun runtimeShape(value: CallGroup): CallGroup = value.copy(
        slug = "", workflows = value.workflows.map { runtimeShape(it) }
    )

    internal fun runtimeShape(value: Workflow): Workflow = value.copy(
        slug = "", nodes = value.nodes.map { runtimeShape(it) }
    )

    internal fun runtimeShape(value: WorkflowNode): WorkflowNode = value.copy(
        slug = "", tagIds = emptyList(), blocks = value.blocks.map { runtimeShape(it) }
    )

    internal fun runtimeShape(value: PromptBlock): PromptBlock = value.copy(slug = "", tagIds = emptyList())

    private fun alias(existing: String, legacy: String, kind: String, id: String, used: Collection<String>): String {
        if (existing.isNotEmpty()) return existing
        val base = if (isValidSlug(legacy)) legacy
            else "${kind}_${UUID.nameUUIDFromBytes(id.toByteArray(Charsets.UTF_8)).toString().take(8)}"
        var candidate = base
        var suffix = 2
        while (candidate in used) candidate = "${base}_${suffix++}"
        return candidate
    }

    /** Reserve explicit aliases before assigning defaults so later siblings retain their chosen names. */
    private fun <T> siblings(values: List<T>, slug: (T) -> String, normalize: (T, Set<String>) -> T): List<T> {
        val used = values.map(slug).filter { it.isNotEmpty() }.toMutableSet()
        return values.map { value -> normalize(value, used).also { used += slug(it) } }.retainOriginal(values)
    }

    private fun <T> T.retainOriginal(original: T): T = if (this == original) original else this
}
