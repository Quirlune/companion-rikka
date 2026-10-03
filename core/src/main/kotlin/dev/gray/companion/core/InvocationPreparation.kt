package dev.gray.companion.core

/** One wave's captured read environment; preparation never changes it. */
internal data class InvocationEnvironment(
    val session: Session,
    val input: String,
    val history: HistoryContext,
    val currentUserMessageId: String?
)

internal data class RenderedInvocationInput(
    val text: String,
    val arguments: Map<String, String>,
    val issues: List<Issue>
)

internal data class InvocationPreparation(
    val input: String,
    val arguments: Map<String, String>,
    val messages: List<ChatMessage>,
    val profile: ModelProfile?
)

/** Rendering and model selection are shared by initial calls and frozen prompt repair. */
internal class InvocationPreparer(private val library: Library) {
    fun renderInput(ref: PlannedNode, environment: InvocationEnvironment): RenderedInvocationInput {
        val session = environment.session
        val issues = mutableListOf<Issue>()
        val renderer = TemplateRenderer(
            loadedResources(library, session, session.state, session.results, environment.input,
                issues, session.resultOrigins, environment.history),
            environment.input, session.results, session.state, issues, session.resultOrigins,
            session, environment.history
        )
        val input = try {
            TextProcessing.apply(ref.node.inputProcessing,
                renderer.render(ref.node.inputTemplate, "node.${ref.node.id}.input"),
                session.state, session.results) {
                renderer.read(it, "node.${ref.node.id}.inputProcessing")
            }
        } catch (_: Exception) {
            throw NodeExecutionFailure(ref, "输入处理失败", RecoveryFailureKind.CONFIGURATION, retryable = false)
        }
        val descriptor = requireNotNull(ref.action).descriptor
        val macroFields = descriptor.fields.filter { it.type == ActionFieldType.MACRO }.mapTo(mutableSetOf()) { it.key }
        val arguments = descriptor.withDefaults(ref.node.arguments).mapValues { (key, value) ->
            if (key in macroFields) value else renderer.render(value, "node.${ref.node.id}.argument.$key")
        }
        return RenderedInvocationInput(input, arguments, issues.toList())
    }

    fun prepare(
        ref: PlannedNode,
        environment: InvocationEnvironment,
        captured: RecoveryPrepared? = null,
        rendered: RenderedInvocationInput? = null
    ): InvocationPreparation {
        val descriptor = requireNotNull(ref.action).descriptor
        val inputs = if (captured != null) RenderedInvocationInput(captured.renderedInput,
            captured.arguments, emptyList()) else rendered ?: renderInput(ref, environment)
        val session = environment.session
        val preview = PromptCompiler().preview(
            library, session, ref.node, environment.input, session.results, session.state,
            environment.currentUserMessageId, session.resultOrigins, environment.history, inputs.text
        )
        val issues = inputs.issues + preview.issues + descriptor.validate(inputs.arguments, "node.${ref.node.id}")
        issues.firstOrNull { it.error }?.let {
            throw NodeExecutionFailure(ref, it.message, RecoveryFailureKind.PROMPT, retryable = false)
        }
        if (ref.node.actionId == "event.emit" && inputs.arguments["event"]?.trim() in
            RuntimeEvents.reserved) {
            throw NodeExecutionFailure(ref, "此事件由运行时派发", RecoveryFailureKind.PROMPT, retryable = false)
        }
        val profile = if (descriptor.usesModel) {
            captured?.profile?.let { saved -> saved.copy(
                resolvedHeaders = ModelCatalog.resolveProfile(library, saved.id)?.resolvedHeaders.orEmpty()
            ) } ?: ref.node.generation.resolve(library, ref.workflow)
        } else null
        if (descriptor.usesModel && (profile == null || profile.model.isBlank())) {
            throw NodeExecutionFailure(ref, "请先选择并填写模型", RecoveryFailureKind.CONFIGURATION, retryable = false)
        }
        return InvocationPreparation(inputs.text, inputs.arguments, preview.messages, profile)
    }
}

internal data class InjectionTarget(val workflowId: String, val nodeId: String) {
    val key: String get() = "$workflowId/$nodeId"
}

internal fun injectionTarget(main: MainCall, actions: Map<String, RegisteredAction>,
                             workflowId: String?, nodeId: String?): InjectionTarget? {
    val workflows = if (workflowId.isNullOrBlank()) main.workflows.filter { it.enabled }
        else main.workflows.filter { it.id == workflowId && it.enabled }
    if (!nodeId.isNullOrBlank()) {
        val matches = workflows.flatMap { workflow -> workflow.nodes.filter {
            it.id == nodeId && it.enabled && actions[it.actionId]?.descriptor?.usesModel == true
        }.map { InjectionTarget(workflow.id, it.id) } }
        return matches.singleOrNull()
    }
    val workflow = if (!workflowId.isNullOrBlank()) workflows.singleOrNull()
        else if (main.primaryWorkflowId != null) workflows.firstOrNull { it.id == main.primaryWorkflowId }
        else workflows.firstOrNull()
    val node = workflow?.nodes?.lastOrNull { it.enabled && actions[it.actionId]?.descriptor?.usesModel == true }
    return node?.let { InjectionTarget(workflow.id, it.id) }
}
