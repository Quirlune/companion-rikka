package dev.gray.companion.core

/** Seeds an unconfigured model node only; custom slot sets and saved worlds are never migrated. */
object DefaultPromptSlots {
    data class Definition(val slug: String, val name: String)
    val definitions = listOf(
        Definition("world_background", "世界背景"), Definition("other_characters", "其他角色"),
        Definition("supplementary_information", "补充信息"), Definition("extra_rules", "额外规则")
    )

    fun ensureMissing(workflow: Workflow): Workflow = if (workflow.nodes.singleOrNull()?.actionId == "model.generate")
        workflow.copy(nodes = listOf(ensureMissing(workflow.nodes.single()))) else workflow

    fun ensureMissing(node: WorkflowNode): WorkflowNode {
        if (node.actionId != "model.generate") return node
        // A resource that already defines its own slots owns that set. Names/slugs are
        // presentation details; treating them as identity creates a second set for imports.
        if (node.blocks.any { it.injectionSlot }) return node
        val blocks = node.blocks.toMutableList()
        definitions.forEach { definition ->
            var slug = definition.slug
            var index = 1
            while (blocks.any { it.slug == slug }) { slug = "${definition.slug}_slot_$index"; index++ }
            var name = definition.name
            index = 1
            while (blocks.any { it.title == name }) { name = "${definition.name}（注入槽$index）"; index++ }
            blocks += PromptBlock(title = name, slug = slug, injectionSlot = true)
        }
        return if (blocks == node.blocks) node else node.copy(blocks = blocks)
    }

    /**
     * Exact, unused default slots can be offered for explicit cleanup when a model node also
     * contains custom slots. No title approximation or partially configured slot is removable.
     */
    fun unusedDefaultDuplicates(node: WorkflowNode, library: Library): List<String> {
        if (node.actionId != "model.generate") return emptyList()
        val slots = node.blocks.filter { it.injectionSlot }
        if (slots.none { defaultDefinition(it) == null }) return emptyList()
        val candidates = slots.filter { defaultDefinition(it) != null }
        if (candidates.isEmpty()) return emptyList()

        val referencedIds = referencedSlotIds(library) ?: return emptyList()
        return candidates.filterNot { it.id in referencedIds }.map { it.id }
    }

    private fun defaultDefinition(block: PromptBlock): Definition? = definitions.firstOrNull { definition ->
        block.title == definition.name && block.slug == definition.slug &&
            block.copy(id = "") == PromptBlock(id = "", title = definition.name, slug = definition.slug, injectionSlot = true)
    }

    /** Only live injectors and current session injections matter; pending runs keep their own frozen worlds. */
    private fun referencedSlotIds(library: Library): Set<String>? {
        val currentSessions = library.sessions + library.savedChats.map { it.snapshot }
        if (currentSessions.any { it.pendingRun != null }) return null
        val referenced = linkedSetOf<String>()
        ResourceLocations.resources(library).forEach { owner ->
            ResourceTemplateSlots.workflows(library, owner).flatMap { it.nodes }
                .filter { it.actionId == "prompt.inject" }
                .mapNotNullTo(referenced) { it.arguments["targetBlockId"]?.trim()?.takeIf(String::isNotBlank) }
        }
        currentSessions.forEach { session ->
            session.promptInjections.mapNotNullTo(referenced) { it.targetBlockId?.trim()?.takeIf(String::isNotBlank) }
        }
        return referenced
    }

    fun referenceError(reference: String, executionVersion: Int = ExecutionSemantics.CURRENT): String? {
        val text = reference.trim()
        val scan = MacroTemplateTokens.scan(text, executionVersion)
        val token = scan.tokens.singleOrNull()
        if (scan.diagnostics.isNotEmpty() || token == null || !token.closed || token.start != 0 || token.endExclusive != text.length)
            return "槽来源须是一个完整的 {{宏引用}}，不能混入字面文本"
        if (isStructuredHistoryBlock(text, executionVersion)) return "结构化 History 不能放入注入槽；文本来源请用 {{history.text}}"
        return macroExpressionError(token.expression, executionVersion)
    }

    fun blockIssues(block: PromptBlock, path: String, executionVersion: Int = ExecutionSemantics.CURRENT): List<Issue> = buildList {
        if (!block.injectionSlot) {
            if (block.slotReferences.isNotEmpty()) add(Issue("$path.slotReferences", "普通提示块不能配置注入槽来源"))
            return@buildList
        }
        if (!ExecutionSemantics(executionVersion).anchoredPromptSlots) add(Issue(path, "此恢复版本不支持注入槽"))
        if (block.template.isNotEmpty()) add(Issue("$path.template", "注入槽只接受来源引用，不能保存字面提示词"))
        if (block.processing.isNotEmpty()) add(Issue("$path.processing", "注入槽不能保存文本处理步骤"))
        block.slotReferences.forEachIndexed { index, reference ->
            referenceError(reference, executionVersion)?.let { add(Issue("$path.slotReferences.$index", it)) }
        }
    }
}
