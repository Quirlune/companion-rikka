package dev.gray.companion.core

/** Saved built-in action configuration rules. Rendered parameters retain their runtime checks. */
internal object ActionConfigurationRules {
    fun outputProcessing(node: WorkflowNode, path: String): List<Issue> =
        if (node.outputProcessing.isNotEmpty() && node.actionId in setOf(
                "state.set", "state.increment", "macro.write", "macro.activate", "macro.process"))
            listOf(Issue("$path.outputProcessing", "此动作的输出处理会与目标值不一致；请在宏处理步骤中修改值"))
        else emptyList()

    fun macroProcess(node: WorkflowNode, path: String): List<Issue> = buildList {
        val config = node.macroProcess
        val target = config.key.ifBlank { node.arguments["key"].orEmpty() }
        if (canonicalWriteAddress(target) == null) add(Issue("$path.macroProcess.key", "宏目标地址无效"))
        if (config.mode == MacroProcessMode.COPY) {
            val source = config.source.trim().removeSurrounding("{{", "}}").trim()
            if (canonicalWriteAddress(source.substringBefore('@')) == null ||
                !validVersionSelector(source.substringAfter('@', "latest")))
                add(Issue("$path.macroProcess.source", "源宏地址或版本选择无效"))
        }
    }

    fun historyArguments(node: WorkflowNode, path: String): List<Issue> = buildList {
        val source = node.arguments["source"] ?: "{{history}}"
        if (source.isBlank()) add(Issue("$path.argument.source", "请选择历史来源"))
        // Saved source acceptance intentionally uses current syntax, including old journals.
        else if (HistoryViews.selection(source) == null)
            add(Issue("$path.argument.source", "来源必须是 History 引用"))
        if (node.actionId == "history.view.copy") {
            if (!MacroNaming.isValidSlug(node.arguments["targetId"].orEmpty()))
                add(Issue("$path.argument.targetId", "历史副本标识须为英文 slug"))
            SafePatterns.validate(node.arguments["pattern"].orEmpty(), node.arguments["captureGroup"]?.toIntOrNull() ?: 0)
                ?.let { add(Issue("$path.argument.pattern", it)) }
        }
        if (node.actionId == "history.memory.select") {
            val count = node.arguments["count"].orEmpty()
            if (count.isNotBlank() && !count.contains("{{") && (count.toIntOrNull()?.let { it >= 0 } != true))
                add(Issue("$path.argument.count", "摘要数量须为非负整数，零表示全部"))
            val mode = node.arguments["mode"] ?: "UNMERGED_LEAVES"
            if (!mode.contains("{{") && mode !in setOf("UNMERGED_LEAVES", "ROOTS"))
                add(Issue("$path.argument.mode", "记忆选择须为未合并小摘要或根摘要"))
        }
    }

    fun textExtraction(node: WorkflowNode, path: String): List<Issue> =
        SafePatterns.validate(node.arguments["pattern"].orEmpty(), node.arguments["captureGroup"]?.toIntOrNull() ?: 0)
            ?.let { listOf(Issue("$path.pattern", it)) }.orEmpty()
}
