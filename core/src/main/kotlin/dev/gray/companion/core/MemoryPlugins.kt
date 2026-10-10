package dev.gray.companion.core

data class MemoryBinding(val extensionId: String?, val ownerId: String, val processor: HistoryProcessor)

/** One Memory interpretation shared by preparation, projection, capture and native editing. */
object MemoryPlugins {
    /** Participation only; the ordinary workflow/node enabled flags remain independent. */
    fun workflowActive(library: Library, session: Session, workflowId: String,
                       executionVersion: Int = ExecutionSemantics.CURRENT): Boolean {
        val nativeOwners = library.extensions.filter { definition -> definition.memory?.let {
            workflowId == it.workflowId || workflowId == it.aggregationWorkflowId
        } == true }
        val main = library.mainCalls.firstOrNull { it.id == session.mainCallId }
        if (nativeOwners.isNotEmpty()) {
            if (!ExecutionSemantics(executionVersion).nativeMemoryPlugins || main == null) return false
            val active = binding(library, session, main, executionVersion)
            return nativeOwners.any { it.id == active?.extensionId }
        }
        if (main != null && workflowId in listOfNotNull(main.history.processor.workflow.id,
                main.history.processor.aggregationWorkflow?.id)) {
            val active = binding(library, session, main, executionVersion)
            return active != null && active.extensionId == null && workflowId in listOfNotNull(
                active.processor.workflow.id, active.processor.aggregationWorkflow?.id)
        }
        return true
    }

    fun binding(library: Library, session: Session, main: MainCall,
                executionVersion: Int = ExecutionSemantics.CURRENT): MemoryBinding? {
        if (ExecutionSemantics(executionVersion).globalMemory && library.memoryConfig != null) {
            if (!library.memoryConfig.enabled) return null
            val extension = GlobalMemory.extension(library) ?: return null
            val processor = processor(library, extension) ?: return null
            return MemoryBinding(extension.id, requireNotNull(extension.memory).groupId, processor)
        }
        if (!ExecutionSemantics(executionVersion).nativeMemoryPlugins)
            return MemoryBinding(null, main.id, main.history.processor)
        val active = PluginParticipation.activeExtensions(library, session, executionVersion).filter { it.memory != null }
        require(active.size <= 1) { "当前会话只能启用一个原生记忆插件" }
        active.singleOrNull()?.let { extension ->
            val processor = requireNotNull(processor(library, extension)) { "原生记忆插件配置或调用组已缺失" }
            return MemoryBinding(extension.id, requireNotNull(extension.memory).groupId, processor)
        }
        return main.history.processor.takeIf { it.workflow.enabled }?.let { MemoryBinding(null, main.id, it) }
    }

    fun processor(library: Library, extension: ConfigurationExtension): HistoryProcessor? {
        val rules = extension.memory ?: return null
        if (ExtensionResourceRef(ExtensionResourceKind.GROUP, rules.groupId) !in extension.resources) return null
        val group = library.groups.singleOrNull { it.id == rules.groupId } ?: return null
        val workflow = group.workflows.singleOrNull { it.id == rules.workflowId } ?: return null
        val aggregation = rules.aggregationWorkflowId?.let { id ->
            group.workflows.singleOrNull { it.id == id } ?: return null
        }
        return HistoryProcessor(workflow = workflow, minimumNewFloors = rules.minimumNewFloors,
            keepRecentFloors = rules.keepRecentFloors, outputKey = rules.outputKey, sourceKind = rules.sourceKind,
            thresholdBasis = rules.thresholdBasis, protectFirstFloors = rules.protectFirstFloors,
            maximumBatchFloors = rules.maximumBatchFloors, aggregationWorkflow = aggregation)
    }

    /** A draft overlay only; guarded commands own admission and persistence. */
    fun withProcessor(library: Library, extension: ConfigurationExtension, group: CallGroup,
                      processor: HistoryProcessor): Library {
        val old = extension.memory
        val ownedIds = listOfNotNull(old?.workflowId, old?.aggregationWorkflowId).toSet()
        val insertion = group.workflows.indexOfFirst { it.id in ownedIds }.let { if (it < 0) group.workflows.size else it }
        val workflows = group.workflows.filterNot { it.id in ownedIds }.toMutableList()
        workflows.addAll(insertion.coerceAtMost(workflows.size), listOf(processor.workflow) + listOfNotNull(processor.aggregationWorkflow))
        val changedGroup = group.copy(workflows = workflows)
        val changedExtension = extension.copy(memory = rules(group.id, processor))
        return library.copy(groups = library.groups.map { if (it.id == group.id) changedGroup else it },
            extensions = library.extensions.map { if (it.id == extension.id) changedExtension else it })
    }

    internal fun rules(groupId: String, processor: HistoryProcessor): MemoryPluginRules = MemoryPluginRules(
        groupId = groupId, workflowId = processor.workflow.id, aggregationWorkflowId = processor.aggregationWorkflow?.id,
        outputKey = processor.outputKey, sourceKind = processor.sourceKind, minimumNewFloors = processor.minimumNewFloors,
        keepRecentFloors = processor.keepRecentFloors, thresholdBasis = processor.thresholdBasis,
        protectFirstFloors = processor.protectFirstFloors, maximumBatchFloors = processor.maximumBatchFloors)

    fun validate(library: Library): List<Issue> = buildList {
        library.extensions.forEach { extension ->
            val rules = extension.memory ?: return@forEach
            val path = "extension.${extension.id}.memory"
            if (rules.groupId.isBlank() || ExtensionResourceRef(ExtensionResourceKind.GROUP, rules.groupId) !in extension.resources)
                add(Issue("$path.groupId", "记忆调用组必须是插件的显式 GROUP 成员"))
            val group = library.groups.singleOrNull { it.id == rules.groupId }
            if (group == null) add(Issue("$path.groupId", "记忆调用组不存在或不唯一"))
            val workflow = group?.workflows?.singleOrNull { it.id == rules.workflowId }
            if (workflow == null) add(Issue("$path.workflowId", "小摘要调用不存在或不属于记忆调用组"))
            else {
                if (workflow.trigger != RuntimeEvents.HISTORY_PROCESS || workflow.afterWorkflowId != null)
                    add(Issue("$path.workflowId", "小摘要须使用 history.process 触发"))
                if (rules.outputKey.isBlank() || workflow.nodes.none { it.outputKey == rules.outputKey })
                    add(Issue("$path.outputKey", "摘要输出键须来自小摘要调用的节点"))
                if (workflow.enabled && workflow.nodes.none { it.enabled && it.outputKey == rules.outputKey })
                    add(Issue("$path.outputKey", "摘要输出键须来自已启用的小摘要节点"))
                if (workflow.enabled && workflow.nodes.any { it.enabled && it.includeHistory && "{{history.pending}}" in it.inputTemplate })
                    add(Issue("$path.workflowId", "自动历史与 pending 输入会重复；请选择一种"))
            }
            if (rules.aggregationWorkflowId != null) {
                val aggregate = group?.workflows?.singleOrNull { it.id == rules.aggregationWorkflowId }
                if (aggregate == null || aggregate.id == rules.workflowId)
                    add(Issue("$path.aggregationWorkflowId", "大摘要调用须是同组中的独立调用"))
                else if (aggregate.afterWorkflowId != rules.workflowId)
                    add(Issue("$path.aggregationWorkflowId", "大摘要须由对应小摘要调用完成触发"))
            }
            if (rules.minimumNewFloors < 1 || rules.keepRecentFloors < 0 || rules.protectFirstFloors < 0 ||
                rules.maximumBatchFloors?.let { it < 1 } == true)
                add(Issue(path, "记忆阈值、保留及批次范围无效"))
        }
        (library.sessions + library.savedChats.map { it.snapshot }).forEach { session ->
            val resources = session.executableWorld?.library ?: library
            val active = PluginParticipation.activeExtensions(resources, session).filter { it.memory != null }
            if (active.size > 1) add(Issue("session.${session.id}.enabledExtensionIds", "当前会话只能启用一个原生记忆插件"))
        }
    }
}
