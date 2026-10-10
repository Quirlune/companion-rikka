package dev.gray.companion.core

import kotlinx.serialization.Serializable

/** A new-turn resource image, never a live fallback or an additional scheduling owner. */
@Serializable data class CapturedMemoryResources(
    val library: Library,
    val workflowIds: Set<String>
)

/** Single resource authority for a planned invocation and its subsequent pure reconstruction. */
object ExecutionResources {
    fun forWorkflow(library: Library, workflowId: String,
                    executionVersion: Int = ExecutionSemantics.CURRENT): Library =
        library.memoryExecutionView?.takeIf {
            ExecutionSemantics(executionVersion).scopedMemoryResources && workflowId in it.workflowIds
        }?.library ?: library

    fun views(library: Library): List<Library> = listOf(library) + listOfNotNull(library.memoryExecutionView?.library)

    fun mapViews(library: Library, transform: (Library) -> Library): Library {
        val view = library.memoryExecutionView
        return transform(library.copy(memoryExecutionView = null)).copy(memoryExecutionView = view?.copy(
            library = transform(view.library.copy(memoryExecutionView = null)).copy(memoryExecutionView = null)))
    }

    fun validate(library: Library, executionVersion: Int? = null): List<Issue> {
        val view = library.memoryExecutionView ?: return emptyList()
        val issues = mutableListOf<Issue>()
        fun requireField(valid: Boolean, message: String) {
            if (!valid) issues += Issue("memoryExecutionView", message)
        }
        requireField(library.schemaVersion >= 24, "此资源版本不支持独立记忆资源环境")
        requireField(executionVersion == null || ExecutionSemantics(executionVersion).scopedMemoryResources,
            "此执行版本不支持独立记忆资源环境")
        val resources = view.library
        requireField(resources.memoryExecutionView == null && resources.sessions.isEmpty() &&
            resources.savedChats.isEmpty() && resources.chatFolders.isEmpty() && resources.selectedSessionId == null &&
            resources.configurationEdits.isEmpty(), "记忆资源环境不能嵌套资源环境、会话或收藏")
        requireField(resources.schemaVersion in 1..LibraryFormat.CURRENT_VERSION, "记忆资源环境版本不受支持")
        val definition = GlobalMemory.extension(resources)
        val owned = definition?.memory?.let { setOfNotNull(it.workflowId, it.aggregationWorkflowId) }.orEmpty()
        requireField(view.workflowIds.isNotEmpty() && view.workflowIds == owned,
            "记忆资源环境须精确对应小摘要和大摘要调用")
        requireField(resources.memoryConfig?.enabled == true && library.memoryConfig?.enabled == true &&
            resources.memoryConfig?.extensionId == library.memoryConfig?.extensionId,
            "记忆资源环境与运行策略不一致")
        requireField(definition?.let { MemoryPlugins.processor(resources, it) } != null,
            "记忆资源环境缺少调用定义")
        return issues
    }
}
