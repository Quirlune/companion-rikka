package dev.gray.companion.core

/** Pure configuration edits. The engine continues to consume only ordinary main bindings. */
object ExtensionCommands {
    fun createDefinition(library: Library, definition: ConfigurationExtension): ExtensionEditResult {
        if (library.extensions.any { it.id == definition.id }) return failure("extension.${definition.id}", "扩展 ID 已存在")
        return finish(library.copy(extensions = library.extensions + definition), "创建扩展：${definition.name}")
    }

    fun updateDefinition(library: Library, definition: ConfigurationExtension): ExtensionEditResult {
        if (library.extensions.none { it.id == definition.id }) return failure("extension.${definition.id}", "扩展不存在")
        val candidate = library.copy(extensions = library.extensions.map { if (it.id == definition.id) definition else it })
        return reconcile(library, candidate, "更新扩展组成：${definition.name}")
    }

    fun deleteDefinition(library: Library, extensionId: String): ExtensionEditResult {
        if (library.extensions.none { it.id == extensionId }) return failure("extension.$extensionId", "扩展不存在")
        if (library.mainCalls.any { main -> main.extensionBindings.any { it.extensionId == extensionId } })
            return failure("extension.$extensionId", "扩展仍已安装，请先在所有主调用中解除安装")
        return finish(library.copy(extensions = library.extensions.filterNot { it.id == extensionId }), "删除扩展定义；保留组成资源")
    }

    fun install(library: Library, mainCallId: String, extensionId: String): ExtensionEditResult {
        if (library.extensions.none { it.id == extensionId }) return failure("extension.$extensionId", "扩展不存在")
        val main = library.mainCalls.firstOrNull { it.id == mainCallId } ?: return failure("main.$mainCallId", "主调用不存在")
        if (main.extensionBindings.any { it.extensionId == extensionId }) return failure("main.$mainCallId.extensions", "此主调用已安装该扩展")
        return finish(replaceMain(library, main.copy(extensionBindings = main.extensionBindings + ExtensionBinding(extensionId = extensionId))), "安装扩展（未启用）")
    }

    fun setEnabled(library: Library, mainCallId: String, bindingId: String, enabled: Boolean): ExtensionEditResult =
        editBinding(library, mainCallId, bindingId, if (enabled) "启用扩展关联" else "停用扩展关联") { it.copy(enabled = enabled) }

    fun detach(library: Library, mainCallId: String, bindingId: String): ExtensionEditResult =
        editBinding(library, mainCallId, bindingId, "解除扩展安装；保留组成资源及实际字段值") { null }

    private fun editBinding(library: Library, mainCallId: String, bindingId: String, change: String,
                            transform: (ExtensionBinding) -> ExtensionBinding?): ExtensionEditResult {
        val main = library.mainCalls.firstOrNull { it.id == mainCallId } ?: return failure("main.$mainCallId", "主调用不存在")
        if (main.extensionBindings.none { it.id == bindingId }) return failure("main.$mainCallId.extensions", "扩展安装不存在")
        val next = main.copy(extensionBindings = main.extensionBindings.mapNotNull { if (it.id == bindingId) transform(it) else it })
        return reconcile(library, replaceMain(library, next), change)
    }

    fun setParameter(library: Library, extensionId: String, parameterId: String, value: String?, expectedValue: String?): ExtensionEditResult {
        val definition = library.extensions.firstOrNull { it.id == extensionId } ?: return failure("extension.$extensionId", "扩展不存在")
        val parameter = definition.parameters.firstOrNull { it.id == parameterId } ?: return failure("extension.$extensionId.parameters", "公开参数不存在")
        if (ExtensionResourceRef(parameter.target.kind, parameter.target.resourceId) !in definition.resources)
            return failure("extension.$extensionId.parameters.$parameterId", "参数目标不在扩展组成中")
        return ExtensionFieldRegistry.write(library, parameter.target, value, expectedValue)
    }

    /** Known, declared associations only; dynamic templates and independent session loads are not scanned. */
    fun impact(library: Library, target: ExtensionFieldRef): ExtensionImpact {
        val ref = ExtensionResourceRef(target.kind, target.resourceId)
        val extensions = library.extensions.filter { ref in it.resources }.map { it.id }
        val mains = library.mainCalls.filter { main ->
            main.extensionBindings.any { it.extensionId in extensions } || when (target.kind) {
                ExtensionResourceKind.MAIN -> main.id == target.resourceId
                ExtensionResourceKind.PACKAGE -> target.resourceId in main.boundPackageIds || library.groups.any {
                    it.id in main.boundGroupIds && target.resourceId in it.boundPackageIds }
                ExtensionResourceKind.GROUP -> target.resourceId in main.boundGroupIds
                ExtensionResourceKind.CHARACTER -> false
            }
        }.map { it.id }
        return ExtensionImpact(mains, extensions)
    }

    /** Used by config preview before diffing, so reconciled main changes join the same strict undo receipt. */
    internal fun reconcile(before: Library, candidate: Library, change: String = "更新扩展关联"): ExtensionEditResult {
        val priorIssues = validate(before).filter { it.error }
        if (priorIssues.isNotEmpty()) return ExtensionEditResult(null, priorIssues)
        val mains = mutableListOf<MainCall>()
        for (main in candidate.mainCalls) {
            val old = before.mainCalls.firstOrNull { it.id == main.id }
            // A newly imported main must carry its own complete, valid ledger; do not invent its provenance.
            if (old == null) {
                mains += main
                continue
            }
            val desired = claims(candidate, main)
            var packages = main.boundPackageIds
            var groups = main.boundGroupIds
            val origins = mutableListOf<ExtensionBindingOrigin>()
            val previous = old.extensionBindingOrigins.associateBy { ExtensionResourceRef(it.kind, it.resourceId) }
            val introduced = main.extensionBindingOrigins.filter { ExtensionResourceRef(it.kind, it.resourceId) !in previous }
            if (introduced.map { it.kind to it.resourceId }.distinct().size != introduced.size)
                return failure("main.${main.id}.extensions", "新增扩展关联来源重复")
            for (origin in introduced) {
                val ref = ExtensionResourceRef(origin.kind, origin.resourceId)
                val bound = when (ref.kind) {
                    ExtensionResourceKind.PACKAGE -> ref.id in packages
                    ExtensionResourceKind.GROUP -> ref.id in groups
                    else -> false
                }
                if (ref.kind !in associationKinds || !exists(candidate, ref) || !bound ||
                    origin.ownerBindingIds.isEmpty() || origin.ownerBindingIds.distinct().size != origin.ownerBindingIds.size ||
                    origin.ownerBindingIds.toSet() != desired[ref].orEmpty().toSet())
                    return failure("main.${main.id}.extensions", "新增扩展关联来源与实际关联或已启用扩展不一致：${ref.id}")
            }
            val supplied = introduced.associateBy { ExtensionResourceRef(it.kind, it.resourceId) }
            for (ref in (previous.keys + desired.keys).distinct()) {
                val origin = previous[ref]
                val owners = desired[ref].orEmpty()
                val bound = if (ref.kind == ExtensionResourceKind.PACKAGE) ref.id in packages else ref.id in groups
                if (owners.isNotEmpty() && origin != null && !bound)
                    return failure("main.${main.id}.extensions", "关联仍由扩展 ${ownerNames(candidate, main, owners)} 使用，不能直接移除：${ref.id}")
                if (owners.isNotEmpty()) {
                    val previouslyBound = if (ref.kind == ExtensionResourceKind.PACKAGE) ref.id in old.boundPackageIds else ref.id in old.boundGroupIds
                    // Existing ownership/manual provenance wins. Only a genuinely new association may supply its origin.
                    val manual = origin?.manual ?: if (previouslyBound) true else supplied[ref]?.manual ?: bound
                    origins += ExtensionBindingOrigin(ref.kind, ref.id, manual, owners)
                    if (!bound) {
                        if (ref.kind == ExtensionResourceKind.PACKAGE) packages = packages + ref.id else groups = groups + ref.id
                    }
                } else if (origin != null && !origin.manual) {
                    if (ref.kind == ExtensionResourceKind.PACKAGE) packages = packages - ref.id else groups = groups - ref.id
                }
            }
            mains += main.copy(boundPackageIds = packages, boundGroupIds = groups, extensionBindingOrigins = origins)
        }
        return finish(candidate.copy(mainCalls = mains), change)
    }

    fun validate(library: Library): List<Issue> = buildList {
        fun unique(ids: List<String>, path: String) {
            if (ids.any { it.isBlank() } || ids.distinct().size != ids.size) add(Issue(path, "ID 不能为空或重复"))
        }
        unique(library.extensions.map { it.id }, "extensions")
        unique(library.extensions.flatMap { it.parameters }.map { it.id }, "extension.parameters")
        unique(library.mainCalls.flatMap { it.extensionBindings }.map { it.id }, "extension.bindings")
        library.extensions.forEach { definition ->
            val path = "extension.${definition.id}"
            if (definition.name.isBlank()) add(Issue("$path.name", "扩展名称不能为空"))
            if (definition.resources.distinct().size != definition.resources.size) add(Issue("$path.resources", "扩展组成不能重复"))
            definition.resources.forEach { ref -> if (!exists(library, ref)) add(Issue("$path.resources.${ref.id}", "扩展组成资源不存在：${ref.kind}")) }
            definition.parameters.forEach { parameter ->
                val at = "$path.parameters.${parameter.id}"
                if (parameter.title.isBlank()) add(Issue(at, "参数标题不能为空"))
                if (ExtensionResourceRef(parameter.target.kind, parameter.target.resourceId) !in definition.resources)
                    add(Issue(at, "参数目标必须属于扩展组成"))
                if (ExtensionFieldRegistry.describe(library, parameter.target) == null) add(Issue(at, "公开字段目标不存在或动作描述符已改变"))
            }
        }
        library.mainCalls.forEach { main ->
            val path = "main.${main.id}.extensions"
            unique(main.extensionBindings.map { it.extensionId }, path)
            main.extensionBindings.forEach { binding -> if (library.extensions.none { it.id == binding.extensionId }) add(Issue(path, "安装的扩展不存在：${binding.extensionId}")) }
            val desired = claims(library, main)
            val origins = main.extensionBindingOrigins
            if (origins.map { it.kind to it.resourceId }.distinct().size != origins.size) add(Issue(path, "扩展关联来源重复"))
            origins.forEach { origin ->
                val ref = ExtensionResourceRef(origin.kind, origin.resourceId)
                if (origin.kind !in associationKinds || !exists(library, ref)) add(Issue(path, "来源仅允许现存的提示词包或调用组"))
                if (origin.ownerBindingIds.isEmpty() || origin.ownerBindingIds.distinct().size != origin.ownerBindingIds.size ||
                    origin.ownerBindingIds.toSet() != desired[ref].orEmpty().toSet()) add(Issue(path, "关联来源与已启用扩展不一致：${origin.resourceId}"))
                val bound = when (origin.kind) {
                    ExtensionResourceKind.PACKAGE -> origin.resourceId in main.boundPackageIds
                    ExtensionResourceKind.GROUP -> origin.resourceId in main.boundGroupIds
                    else -> false
                }
                if (!bound) add(Issue(path, "关联仍由扩展 ${ownerNames(library, main, origin.ownerBindingIds)} 使用，不能直接移除：${origin.resourceId}"))
            }
            desired.forEach { (ref, _) -> if (origins.none { it.kind == ref.kind && it.resourceId == ref.id }) add(Issue(path, "已启用扩展缺少关联来源：${ref.id}")) }
        }
    }

    fun withoutMetadata(main: MainCall): MainCall = main.copy(extensionBindings = emptyList(), extensionBindingOrigins = emptyList())
    fun withoutMetadata(library: Library): Library = library.copy(extensions = emptyList(), mainCalls = library.mainCalls.map(::withoutMetadata))

    private val associationKinds = setOf(ExtensionResourceKind.PACKAGE, ExtensionResourceKind.GROUP)
    private fun claims(library: Library, main: MainCall): Map<ExtensionResourceRef, List<String>> {
        val result = linkedMapOf<ExtensionResourceRef, MutableList<String>>()
        main.extensionBindings.filter { it.enabled }.forEach { binding ->
            library.extensions.firstOrNull { it.id == binding.extensionId }?.resources.orEmpty().filter { it.kind in associationKinds }.forEach {
                result.getOrPut(it) { mutableListOf() }.add(binding.id)
            }
        }
        return result
    }
    private fun exists(library: Library, ref: ExtensionResourceRef): Boolean = when (ref.kind) {
        ExtensionResourceKind.CHARACTER -> library.characters.any { it.id == ref.id }
        ExtensionResourceKind.MAIN -> library.mainCalls.any { it.id == ref.id }
        ExtensionResourceKind.PACKAGE -> library.packages.any { it.id == ref.id }
        ExtensionResourceKind.GROUP -> library.groups.any { it.id == ref.id }
    }
    private fun ownerNames(library: Library, main: MainCall, owners: List<String>): String = main.extensionBindings
        .filter { it.id in owners }.map { binding -> library.extensions.firstOrNull { it.id == binding.extensionId }?.name ?: binding.extensionId }.joinToString("、")
    private fun replaceMain(library: Library, main: MainCall) = library.copy(mainCalls = library.mainCalls.map { if (it.id == main.id) main else it })
    private fun finish(candidate: Library, change: String): ExtensionEditResult {
        val issues = LibraryValidator.validate(candidate).filter { it.error }
        return ExtensionEditResult(candidate.takeIf { issues.isEmpty() }, issues, listOf(change))
    }
    private fun failure(path: String, message: String) = ExtensionEditResult(null, listOf(Issue(path, message)))
}
