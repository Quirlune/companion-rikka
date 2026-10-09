package dev.gray.companion.core

/** Independent collections point to real resources and fields. They own no runtime loading state. */
object ExtensionCommands {
    fun createDefinition(library: Library, definition: ConfigurationExtension): ExtensionEditResult {
        if (library.extensions.any { it.id == definition.id }) return failure("extension.${definition.id}", "集合 ID 已存在")
        return finish(library.copy(extensions = library.extensions + definition), "创建集合：${definition.name}")
    }

    fun updateDefinition(library: Library, definition: ConfigurationExtension): ExtensionEditResult {
        if (library.extensions.none { it.id == definition.id }) return failure("extension.${definition.id}", "集合不存在")
        return finish(library.copy(extensions = library.extensions.map { if (it.id == definition.id) definition else it }),
            "更新集合组成：${definition.name}；保留现有资源关联")
    }

    fun deleteDefinition(library: Library, extensionId: String): ExtensionEditResult {
        if (library.extensions.none { it.id == extensionId }) return failure("extension.$extensionId", "集合不存在")
        // Built-in Memory is a separate system entry, never a name-based exception in this catalogue.
        return finish(library.copy(extensions = library.extensions.filterNot { it.id == extensionId }),
            "删除集合定义；保留所有组成资源、字段值及实际关联")
    }

    @Deprecated("Collections are independent; edit ordinary resource associations directly")
    @Suppress("UNUSED_PARAMETER")
    fun install(library: Library, mainCallId: String, extensionId: String): ExtensionEditResult =
        retired("extension.$extensionId")

    @Deprecated("Collections are independent and have no enabled state")
    @Suppress("UNUSED_PARAMETER")
    fun setEnabled(library: Library, mainCallId: String, bindingId: String, enabled: Boolean): ExtensionEditResult =
        retired("main.$mainCallId.extensions")

    @Deprecated("Collections are independent; deleting a definition preserves resources and associations")
    @Suppress("UNUSED_PARAMETER")
    fun detach(library: Library, mainCallId: String, bindingId: String): ExtensionEditResult =
        retired("main.$mainCallId.extensions")

    private fun retired(path: String) = failure(path, "扩展现为独立集合，不再安装、启停或解除安装；请在原编辑器直接修改资源关联")

    fun setParameter(library: Library, extensionId: String, parameterId: String, value: String?, expectedValue: String?): ExtensionEditResult {
        val definition = library.extensions.firstOrNull { it.id == extensionId } ?: return failure("extension.$extensionId", "集合不存在")
        val parameter = definition.parameters.firstOrNull { it.id == parameterId } ?: return failure("extension.$extensionId.parameters", "公开参数不存在")
        if (ExtensionResourceRef(parameter.target.kind, parameter.target.resourceId) !in definition.resources)
            return failure("extension.$extensionId.parameters.$parameterId", "参数目标不在集合组成中")
        return ExtensionFieldRegistry.write(library, parameter.target, value, expectedValue)
    }

    /** Direct actual associations only; a collection's membership does not load anything. */
    fun impact(library: Library, target: ExtensionFieldRef): ExtensionImpact {
        val ref = ExtensionResourceRef(target.kind, target.resourceId)
        val extensions = library.extensions.filter { ref in it.resources }.map { it.id }
        val mains = library.mainCalls.filter { main -> when (target.kind) {
            ExtensionResourceKind.MAIN -> main.id == target.resourceId
            ExtensionResourceKind.PACKAGE -> target.resourceId in main.boundPackageIds || library.groups.any {
                it.id in main.boundGroupIds && target.resourceId in it.boundPackageIds }
            ExtensionResourceKind.GROUP -> target.resourceId in main.boundGroupIds
            ExtensionResourceKind.CHARACTER -> false
        } }.map { it.id }
        return ExtensionImpact(mains, extensions)
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
        val issues = LibraryValidator.validate(canonical).filter { it.error }
        return ExtensionEditResult(canonical.takeIf { issues.isEmpty() }, issues, listOf(change))
    }
    private fun failure(path: String, message: String) = ExtensionEditResult(null, listOf(Issue(path, message)))
}
