package dev.gray.companion.core

/** Explicit recovery-only editing. Ordinary retries never refresh captured definitions from live. */
object RecoveryPromptEdits {
    fun resources(recovery: RunRecovery, nodeKey: String): Library =
        ExecutionResources.forWorkflow(recovery.frozenResources, nodeKey.substringBefore('/'), recovery.executionVersion)

    fun node(recovery: RunRecovery, nodeKey: String): WorkflowNode {
        val library = resources(recovery, nodeKey)
        val workflowId = nodeKey.substringBefore('/')
        val nodeId = nodeKey.substringAfter('/', "")
        require(nodeId.isNotEmpty()) { "断点节点标识无效" }
        return (library.mainCalls.flatMap { it.configuredWorkflows() } + library.groups.flatMap { it.workflows })
            .singleOrNull { it.id == workflowId }?.nodes?.singleOrNull { it.id == nodeId }
            ?: throw IllegalArgumentException("断点调用定义不存在或不唯一")
    }

    fun apply(recovery: RunRecovery, nodeKey: String, blocks: List<PromptBlock>): RunRecovery {
        require(ExecutionSemantics(recovery.executionVersion).scopedMemoryResources) { "旧断点仍使用原提示词修复方式" }
        require(recovery.failures.any { it.nodeKey == nodeKey } && recovery.accepted.none { it.nodeKey == nodeKey }) {
            "只能编辑未接受的失败调用提示词"
        }
        node(recovery, nodeKey)
        val workflowId = nodeKey.substringBefore('/')
        val nodeId = nodeKey.substringAfter('/')
        fun patch(library: Library): Library {
            fun flow(value: Workflow): Workflow = if (value.id != workflowId) value else value.copy(
                nodes = value.nodes.map { if (it.id == nodeId) it.copy(blocks = blocks) else it })
            return library.copy(mainCalls = library.mainCalls.map { main -> main.copy(
                workflows = main.workflows.map(::flow), history = main.history.copy(processor = main.history.processor.copy(
                    workflow = flow(main.history.processor.workflow),
                    aggregationWorkflow = main.history.processor.aggregationWorkflow?.let(::flow)))) },
                groups = library.groups.map { it.copy(workflows = it.workflows.map(::flow)) })
        }
        val frozen = recovery.frozenResources
        val view = frozen.memoryExecutionView
        val candidate = patch(frozen).copy(memoryExecutionView = view?.let {
            if (workflowId in it.workflowIds) it.copy(library = patch(it.library)) else it
        })
        ExecutionResources.views(candidate).flatMap { LibraryValidator.validate(it, recovery.executionVersion) }
            .firstOrNull { it.error }?.let { throw IllegalArgumentException(it.message) }
        return recovery.copy(frozenResources = candidate)
    }
}
