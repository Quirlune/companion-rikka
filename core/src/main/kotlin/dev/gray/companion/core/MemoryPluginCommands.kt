package dev.gray.companion.core

/** Native Memory config edits and participation never rewrite summaries, source messages or old worlds. */
object MemoryPluginCommands {
    fun create(library: Library, processor: HistoryProcessor = HistoryProcessor(),
               expectedMain: MainCall? = null): ExtensionEditResult {
        if (expectedMain != null && library.mainCalls.firstOrNull { it.id == expectedMain.id } != expectedMain)
            return failure("main.${expectedMain.id}.history.processor", "主调用记忆配置已改变，请重新打开后转移")
        val transferring = if (expectedMain == null) emptyList() else library.sessions.filter {
            it.mainCallId == expectedMain.id && it.executableWorld == null
        }
        if (transferring.any { it.pendingRun != null })
            return failure("main.${expectedMain?.id}.history.processor", "仍有使用此主调用的会话断点，请先结束或恢复该轮，再转移记忆")
        if (transferring.any { session -> PluginParticipation.activeExtensions(library, session).any { it.memory != null } })
            return failure("main.${expectedMain?.id}.history.processor", "使用此主调用的会话已启用另一项原生记忆插件，请先关闭它再转移")
        val selected = expectedMain?.history?.processor ?: processor.copy(workflow = processor.workflow.copy(enabled = true))
        val group = CallGroup(name = "记忆调用组", workflows = listOf(selected.workflow) + listOfNotNull(selected.aggregationWorkflow))
        val definition = ConfigurationExtension(name = if (expectedMain == null) "记忆" else "记忆 · ${expectedMain.name}",
            resources = listOf(ExtensionResourceRef(ExtensionResourceKind.GROUP, group.id)), memory = MemoryPlugins.rules(group.id, selected))
        val candidate = library.copy(groups = library.groups + group, extensions = library.extensions + definition,
            sessions = if (expectedMain?.history?.processor?.workflow?.enabled == true) library.sessions.map { session ->
                if (session in transferring) session.copy(enabledExtensionIds = (session.enabledExtensionIds + definition.id).distinct()) else session
            } else library.sessions,
            mainCalls = if (expectedMain == null) library.mainCalls else library.mainCalls.map { main ->
                if (main.id == expectedMain.id) main.copy(history = main.history.copy(processor = HistoryProcessor())) else main
            })
        val result = finish(candidate, if (expectedMain == null) "创建原生记忆插件；当前会话尚未启用"
            else "将主调用记忆原样转移到插件；保留当前聊天的原启用状态、摘要、来源和旧环境")
        return if (result.library == null && expectedMain != null) result.copy(issues = result.issues +
            Issue("main.${expectedMain.id}.history.processor", "转移未应用；请先按错误定位处理原主调用的工作流引用，不能自动改写来源")) else result
    }

    fun update(library: Library, expected: ConfigurationExtension, expectedGroup: CallGroup,
               processor: HistoryProcessor): ExtensionEditResult {
        if (library.extensions.firstOrNull { it.id == expected.id } != expected || expected.memory == null)
            return failure("extension.${expected.id}.memory", "记忆插件已改变，请重新打开后保存")
        if (expected.memory.groupId != expectedGroup.id || library.groups.firstOrNull { it.id == expectedGroup.id } != expectedGroup)
            return failure("group.${expectedGroup.id}", "记忆调用组已改变，请重新打开后保存")
        return finish(MemoryPlugins.withProcessor(library, expected, expectedGroup, processor), "更新记忆插件规则与调用；已保存摘要和旧环境保留")
    }

    fun setParticipation(library: Library, expected: ConfigurationExtension, sessionId: String,
                         included: Boolean, expectedIncluded: Boolean, endPending: Boolean = false): ExtensionEditResult {
        if (library.memoryConfig != null)
            return failure("memoryConfig", "记忆已使用全局开关，不再按会话设置参与状态")
        if (library.extensions.firstOrNull { it.id == expected.id } != expected || expected.memory == null)
            return failure("extension.${expected.id}.memory", "记忆插件已改变，请重新打开后切换")
        if (library.selectedSessionId != sessionId) return failure("session.$sessionId", "当前会话已切换")
        val session = library.sessions.firstOrNull { it.id == sessionId }
            ?: return failure("session.$sessionId", "当前会话不存在")
        if ((expected.id in session.enabledExtensionIds) != expectedIncluded)
            return failure("session.$sessionId.plugins", "插件参与状态已改变，请重新打开后切换")
        if (session.pendingRun != null && (included || !endPending))
            return failure("session.$sessionId", "请先处理当前会话断点；关闭并结束本轮需明确选择")
        val resources = session.executableWorld?.library ?: library
        if (included && resources.extensions.none { it.id == expected.id && it.memory != null })
            return failure("session.$sessionId.plugins", "旧环境没有此插件，请新建聊天使用当前配置")
        if (included && PluginParticipation.activeExtensions(resources, session).any { it.id != expected.id && it.memory != null })
            return failure("session.$sessionId.plugins", "当前会话已经启用另一项原生记忆插件，请先关闭它")
        val pending = session.pendingRun
        val next = session.copy(enabledExtensionIds = if (included) (session.enabledExtensionIds + expected.id).distinct()
            else session.enabledExtensionIds.filterNot { it == expected.id },
            pendingRun = if (!included && endPending) null else pending,
            unavailableResultKeys = if (!included && endPending && pending != null && !pending.regenerating)
                pending.unavailableKeys else session.unavailableResultKeys)
        return finish(library.copy(sessions = library.sessions.map { if (it.id == sessionId) next else it }),
            if (included) "原生记忆参与当前会话" else "关闭记忆活动投影与生成；保留已保存摘要、正文、来源和分支")
    }

    private fun finish(candidate: Library, change: String): ExtensionEditResult {
        val canonical = CollectionCompatibility.canonicalLive(candidate)
        val issues = (LibraryValidator.validate(canonical) + MemoryPlugins.validate(canonical)).filter { it.error }.distinct()
        return ExtensionEditResult(canonical.takeIf { issues.isEmpty() }, issues, listOf(change))
    }
    private fun failure(path: String, message: String) = ExtensionEditResult(null, listOf(Issue(path, message)))
}
