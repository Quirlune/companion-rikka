package dev.gray.companion.core

/** Draft/default creation only. This helper never migrates existing libraries or saved worlds. */
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
        val blocks = node.blocks.toMutableList()
        definitions.forEach { definition ->
            if (blocks.any { it.injectionSlot && (it.slug == definition.slug || it.title == definition.name ||
                    it.slug.startsWith("${definition.slug}_slot") && it.title.startsWith(definition.name)) }) return@forEach
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
