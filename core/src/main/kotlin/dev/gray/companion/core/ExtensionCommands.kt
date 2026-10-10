package dev.gray.companion.core

/** Global extension policy edits real resources; historical session participation remains readable. */
object ExtensionCommands {
    fun setEnabled(library: Library, expected: ConfigurationExtension, enabled: Boolean): ExtensionEditResult {
        if (expected.memory != null) return failure("extension.${expected.id}", "记忆请使用原生记忆开关")
        if (library.extensions.firstOrNull { it.id == expected.id } != expected)
            return failure("extension.${expected.id}", "扩展定义已改变，请刷新后切换")
        val candidate = library.copy(extensions = library.extensions.map {
            if (it.id == expected.id) it.copy(enabled = enabled) else it
        })
        return finish(candidate.copy(sessions = candidate.sessions.map { session ->
            if (enabled) session else session.copy(promptInjections = session.promptInjections.filter {
                PluginParticipation.injectionActive(candidate, session, it.pluginOwnerIds, it.origin.resourceId)
            })
        }), if (enabled) "已开启扩展" else "已关闭扩展；历史数据保留")
    }

    fun createDefinition(library: Library, definition: ConfigurationExtension): ExtensionEditResult {
        if (library.extensions.any { it.id == definition.id }) return failure("extension.${definition.id}", "集合 ID 已存在")
        val live = if (definition.memory == null && definition.enabled == null) definition.copy(enabled = false) else definition
        return finish(library.copy(extensions = library.extensions + live), "创建集合：${definition.name}")
    }

    fun updateDefinition(library: Library, definition: ConfigurationExtension): ExtensionEditResult {
        if (library.extensions.none { it.id == definition.id }) return failure("extension.${definition.id}", "集合不存在")
        return finish(library.copy(extensions = library.extensions.map { if (it.id == definition.id) definition else it }),
            "更新集合组成：${definition.name}；保留现有资源关联")
    }

    fun deleteDefinition(library: Library, extensionId: String): ExtensionEditResult {
        val definition = library.extensions.firstOrNull { it.id == extensionId } ?: return failure("extension.$extensionId", "集合不存在")
        if (definition.memory != null)
            return failure("extension.$extensionId.memory", "原生记忆请通过会话开关关闭；保存的规则与摘要保留")
        if (library.sessions.any { extensionId in it.enabledExtensionIds && it.pendingRun != null })
            return failure("extension.$extensionId", "仍有参与此插件的会话断点，请先恢复或结束该轮")
        // Ordinary collection deletion preserves its shared resources and accepted archives.
        return finish(library.copy(extensions = library.extensions.filterNot { it.id == extensionId },
            sessions = library.sessions.map { PluginParticipation.disable(it, extensionId) }),
            "删除插件定义并撤销其会话参与；保留组成资源、共享关联和历史正文")
    }

    fun setParticipation(library: Library, expected: ConfigurationExtension, sessionId: String,
                         included: Boolean, expectedIncluded: Boolean): ExtensionEditResult {
        if (expected.memory != null)
            return MemoryPluginCommands.setParticipation(library, expected, sessionId, included, expectedIncluded)
        if (library.extensions.firstOrNull { it.id == expected.id } != expected)
            return failure("extension.${expected.id}", "插件定义已改变，请重新打开后切换")
        if (library.selectedSessionId != sessionId) return failure("session.$sessionId", "当前会话已切换")
        val session = library.sessions.firstOrNull { it.id == sessionId } ?: return failure("session.$sessionId", "当前会话不存在")
        if (session.pendingRun != null) return failure("session.$sessionId", "请先处理当前会话断点，再切换插件")
        if (included && session.executableWorld?.library?.extensions?.none { it.id == expected.id } == true)
            return failure("session.$sessionId.plugins", "旧环境没有此插件，请新建聊天使用当前配置")
        if ((expected.id in session.enabledExtensionIds) != expectedIncluded)
            return failure("session.$sessionId.plugins", "插件参与状态已改变，请重新打开后切换")
        val next = if (included) session.copy(enabledExtensionIds = (session.enabledExtensionIds + expected.id).distinct())
            else PluginParticipation.disable(session, expected.id)
        return finish(library.copy(sessions = library.sessions.map { if (it.id == sessionId) next else it }),
            if (included) "插件参与当前会话；主调用与手工关联保留" else "关闭当前会话插件并撤销其活动注入；历史正文和共享状态保留")
    }

    /** Create one ordinary display owner and its collection reference together, without a main/session. */
    fun addDisplayGroup(library: Library, expected: ConfigurationExtension, group: CallGroup): ExtensionEditResult {
        if (library.extensions.firstOrNull { it.id == expected.id } != expected)
            return failure("extension.${expected.id}", "扩展定义已改变，请重新打开后添加磁贴配置")
        if (library.groups.any { it.id == group.id })
            return failure("group.${group.id}", "磁贴配置资源 ID 已存在")
        val next = expected.copy(resources = expected.resources + ExtensionResourceRef(ExtensionResourceKind.GROUP, group.id))
        return finish(library.copy(groups = library.groups + group,
            extensions = library.extensions.map { if (it.id == expected.id) next else it }), "添加扩展磁贴配置：${group.name}")
    }

    /** Actual session association only, never a collection enable ledger or a main-call edit. */
    fun setDisplayGroupForSession(library: Library, expected: ConfigurationExtension,
                                  sessionId: String, groupId: String, included: Boolean): ExtensionEditResult {
        if (library.extensions.firstOrNull { it.id == expected.id } != expected)
            return failure("extension.${expected.id}", "扩展定义已改变，请重新打开后修改会话关联")
        if (ExtensionResourceRef(ExtensionResourceKind.GROUP, groupId) !in expected.resources || library.groups.none { it.id == groupId })
            return failure("extension.${expected.id}.resources", "磁贴配置不在当前扩展组成中或已不存在")
        if (library.selectedSessionId != sessionId)
            return failure("session.$sessionId", "当前会话已切换，请重新选择磁贴配置")
        val session = library.sessions.firstOrNull { it.id == sessionId }
            ?: return failure("session.$sessionId", "当前会话不存在")
        if (session.pendingRun != null)
            return failure("session.$sessionId", "当前会话仍有断点，请先恢复或结束这一轮，再修改磁贴关联")
        val groups = if (included) (session.groupIds + groupId).distinct() else session.groupIds.filterNot { it == groupId }
        return finish(library.copy(sessions = library.sessions.map {
            if (it.id == sessionId) it.copy(groupIds = groups) else it
        }), if (included) "磁贴配置加入当前会话" else "移除当前会话的磁贴配置关联；资源及角色、主调用关联保留")
    }

    fun setParameter(library: Library, extensionId: String, parameterId: String, value: String?, expectedValue: String?): ExtensionEditResult {
        val definition = library.extensions.firstOrNull { it.id == extensionId } ?: return failure("extension.$extensionId", "集合不存在")
        val parameter = definition.parameters.firstOrNull { it.id == parameterId } ?: return failure("extension.$extensionId.parameters", "公开参数不存在")
        if (ExtensionResourceRef(parameter.target.kind, parameter.target.resourceId) !in definition.resources)
            return failure("extension.$extensionId.parameters.$parameterId", "参数目标不在集合组成中")
        return ExtensionFieldRegistry.write(library, parameter.target, value, expectedValue)
    }

    fun validate(library: Library): List<Issue> = buildList {
        fun unique(ids: List<String>, path: String) {
            if (ids.any { it.isBlank() } || ids.distinct().size != ids.size) add(Issue(path, "ID 不能为空或重复"))
        }
        unique(library.extensions.map { it.id }, "extensions")
        unique(library.extensions.flatMap { it.parameters }.map { it.id }, "extension.parameters")
        library.extensions.forEach { definition ->
            val path = "extension.${definition.id}"
            if (definition.name.isBlank()) add(Issue("$path.name", "集合名称不能为空"))
            if (definition.resources.distinct().size != definition.resources.size) add(Issue("$path.resources", "集合组成不能重复"))
            definition.resources.forEach { ref -> if (!exists(library, ref)) add(Issue("$path.resources.${ref.id}", "集合组成资源不存在：${ref.kind}")) }
            definition.parameters.forEach { parameter ->
                val at = "$path.parameters.${parameter.id}"
                if (parameter.title.isBlank()) add(Issue(at, "参数标题不能为空"))
                if (ExtensionResourceRef(parameter.target.kind, parameter.target.resourceId) !in definition.resources)
                    add(Issue(at, "参数目标必须属于集合组成"))
                if (ExtensionFieldRegistry.describe(library, parameter.target) == null) add(Issue(at, "公开字段目标不存在或动作描述符已改变"))
            }
        }
    }

    fun withoutMetadata(main: MainCall): MainCall = CollectionCompatibility.canonicalMain(main)
    fun withoutMetadata(library: Library): Library = library.copy(extensions = emptyList(), mainCalls = library.mainCalls.map(::withoutMetadata))

    private fun exists(library: Library, ref: ExtensionResourceRef): Boolean = when (ref.kind) {
        ExtensionResourceKind.CHARACTER -> library.characters.any { it.id == ref.id }
        ExtensionResourceKind.MAIN -> library.mainCalls.any { it.id == ref.id }
        ExtensionResourceKind.PACKAGE -> library.packages.any { it.id == ref.id }
        ExtensionResourceKind.GROUP -> library.groups.any { it.id == ref.id }
    }
    private fun finish(candidate: Library, change: String): ExtensionEditResult {
        val canonical = CollectionCompatibility.canonicalLive(candidate)
        val issues = (LibraryValidator.validate(canonical) + MemoryPlugins.validate(canonical)).filter { it.error }.distinct()
        return ExtensionEditResult(canonical.takeIf { issues.isEmpty() }, issues, listOf(change))
    }
    private fun failure(path: String, message: String) = ExtensionEditResult(null, listOf(Issue(path, message)))
}
