package dev.gray.companion.core

/** One wave's captured read environment; preparation never changes it. */
internal data class InvocationEnvironment(
    val session: Session,
    val input: String,
    val history: HistoryContext,
    val currentUserMessageId: String?,
    val executionVersion: Int = ExecutionSemantics.CURRENT,
    val historyReadsByKey: Map<String, HistoryPromptRead> = emptyMap(),
    val macroTarget: MacroTargetSnapshot? = null
)

internal data class RenderedInvocationInput(
    val text: String,
    val arguments: Map<String, String>,
    val issues: List<Issue>,
    val inputSourceIds: List<String>? = null
)

internal data class InvocationPreparation(
    val input: String,
    val arguments: Map<String, String>,
    val messages: List<ChatMessage>,
    val profile: ModelProfile?,
    val historyRead: HistoryPromptRead? = null,
    val inputSourceIds: List<String>? = null
)

/** Rendering and model selection are shared by initial calls and frozen prompt repair. */
internal class InvocationPreparer(private val library: Library) {
    fun renderInput(ref: PlannedNode, environment: InvocationEnvironment): RenderedInvocationInput {
        val library = ExecutionResources.forWorkflow(library, ref.workflow.id, environment.executionVersion)
        val environment = environment.copy(history = environment.history.withResources(library))
        val tag = GlobalMemory.summaryXmlTag(library, ref.workflow.id, ref.node.id, environment.executionVersion)
        val session = if (tag == null) environment.session else environment.session.copy(
            state = environment.session.state + ("memory_summary_xml_tag" to tag))
        val issues = mutableListOf<Issue>()
        val inputSourceIds = linkedSetOf<String>()
        val memoryScope = MemoryPromptScope.forInvocation(library, ref, environment)
        val renderer = TemplateRenderer(
            loadedResources(library, session, session.state, session.results, environment.input,
                issues, session.resultOrigins, environment.history, environment.executionVersion),
            environment.input, session.results, session.state, issues, session.resultOrigins,
            session, environment.history, environment.executionVersion, memoryScope,
            onHistoryRead = { inputSourceIds += it }
        )
        val input = try { renderer.captureHistoryReads {
            TextProcessing.apply(ref.node.inputProcessing,
                renderer.render(ref.node.inputTemplate, "node.${ref.node.id}.input"),
                session.state, session.results, environment.executionVersion) {
                renderer.read(it, "node.${ref.node.id}.inputProcessing")
            }
        } } catch (_: Exception) {
            throw NodeExecutionFailure(ref, "输入处理失败", RecoveryFailureKind.CONFIGURATION, retryable = false)
        }
        val descriptor = requireNotNull(ref.action).descriptor
        val macroFields = descriptor.fields.filter { it.type == ActionFieldType.MACRO }.mapTo(mutableSetOf()) { it.key }
        val arguments = descriptor.withDefaults(ref.node.arguments).mapValues { (key, value) ->
            // History sources are read references, not writable text macro addresses. Keep the selector intact.
            if (key in macroFields || (ref.node.actionId.startsWith("history.") && key == "source")) value
            else renderer.render(value, "node.${ref.node.id}.argument.$key")
        }
        return RenderedInvocationInput(input, arguments, issues.toList(), inputSourceIds.toList())
    }

    fun prepare(
        ref: PlannedNode,
        environment: InvocationEnvironment,
        captured: RecoveryPrepared? = null,
        rendered: RenderedInvocationInput? = null
    ): InvocationPreparation {
        val library = ExecutionResources.forWorkflow(library, ref.workflow.id, environment.executionVersion)
        val environment = environment.copy(history = environment.history.withResources(library))
        val descriptor = requireNotNull(ref.action).descriptor
        val inputs = if (captured != null) RenderedInvocationInput(captured.renderedInput,
            captured.arguments, emptyList(), captured.inputSourceIds) else rendered ?: renderInput(ref, environment)
        val tag = GlobalMemory.summaryXmlTag(library, ref.workflow.id, ref.node.id, environment.executionVersion)
        val session = if (tag == null) environment.session else environment.session.copy(
            state = environment.session.state + ("memory_summary_xml_tag" to tag))
        val memoryScope = try { MemoryPromptScope.forInvocation(library, ref, environment) }
            catch (failure: IllegalArgumentException) {
                throw NodeExecutionFailure(ref, failure.message.orEmpty(), RecoveryFailureKind.CONFIGURATION, retryable = false)
            }
        val preview = PromptCompiler().preview(
            library, session, ref.node, environment.input, session.results, session.state,
            environment.currentUserMessageId, session.resultOrigins, environment.history, inputs.text, environment.executionVersion,
            memoryScope, preprocessedInputSourceIds = inputs.inputSourceIds
        )
        val configIssues = if (tag != null) emptyList() else RegexOutputCopies.validate(ref.node.regexOutputCopy, "node.${ref.node.id}.regexOutputCopy") +
            if (ref.node.regexOutputCopy.enabled && ref.node.regexOutputCopy.outputKey == ref.node.outputKey)
                listOf(Issue("node.${ref.node.id}.regexOutputCopy", "副本与原始输出不能使用同一个宏地址")) else emptyList()
        configIssues.firstOrNull { it.error }?.let {
            throw NodeExecutionFailure(ref, it.message, RecoveryFailureKind.CONFIGURATION, retryable = false)
        }
        val issues = inputs.issues + preview.issues + descriptor.validate(inputs.arguments, "node.${ref.node.id}")
        issues.firstOrNull { it.error }?.let {
            throw NodeExecutionFailure(ref, it.message, RecoveryFailureKind.PROMPT, retryable = false)
        }
        val historyRead = memoryScope?.let { scope ->
            // COPY does not consume the prompt: observe its native source reader instead.
            val copy = ref.node.macroProcess.takeIf { ref.node.actionId == "macro.process" && it.mode == MacroProcessMode.COPY }
            if (copy == null) scope.manifest(preview.historySourceIds) else {
                val ids = mutableListOf<String>()
                try {
                    readCopySource(library, session, copy.source, environment.macroTarget) { address, value, version ->
                        ids += scope.resultReadSourceIds(address, value, version)
                    }
                } catch (failure: IllegalArgumentException) {
                    throw NodeExecutionFailure(ref, failure.message.orEmpty(), RecoveryFailureKind.PROMPT, retryable = false)
                } catch (failure: IllegalStateException) {
                    throw NodeExecutionFailure(ref, failure.message.orEmpty(), RecoveryFailureKind.PROMPT, retryable = false)
                }
                scope.manifest(ids)
            }
        }
        if (descriptor.usesModel && historyRead != null) {
            val definition = GlobalMemory.extension(library)
            val processor = definition?.let { MemoryPlugins.processor(library, it) }
            val smallProducer = processor?.let { GlobalMemory.modelNode(it.workflow, it.outputKey) }
            val largeProducer = processor?.aggregationWorkflow?.let { GlobalMemory.modelNode(it) }
            if (ref.workflow.id == processor?.workflow?.id && ref.node.id == smallProducer?.id && historyRead.sources.isEmpty())
                throw NodeExecutionFailure(ref, "小摘要提示词须实际引用可处理的正文楼层", RecoveryFailureKind.PROMPT, retryable = false)
            if (ref.workflow.id == processor?.aggregationWorkflow?.id && ref.node.id == largeProducer?.id && historyRead.childSegmentIds.size < 2)
                throw NodeExecutionFailure(ref, "大摘要提示词须实际引用至少两段完整小摘要", RecoveryFailureKind.PROMPT, retryable = false)
        }
        val emittedEvent = inputs.arguments["event"].orEmpty().trim()
        if (ref.node.actionId == "event.emit" && ExecutionSemantics(environment.executionVersion).isReservedEvent(emittedEvent)) {
            throw NodeExecutionFailure(ref, "此事件由运行时派发", RecoveryFailureKind.PROMPT, retryable = false)
        }
        val profile = if (descriptor.usesModel) {
            captured?.profile?.let { saved ->
                // The supplied library is the captured world on resume. Rebuild only transient transport
                // values; the saved profile retains the wave's already-resolved generation overrides.
                val resolved = ModelCatalog.resolveProfile(library, saved.id)
                saved.copy(resolvedHeaders = resolved?.resolvedHeaders.orEmpty(),
                    resolvedProvider = resolved?.resolvedProvider,
                    reasoning = saved.reasoning ?: ModelReasoning.OFF.takeUnless {
                        ExecutionSemantics(environment.executionVersion).behaviorOnlyGeneration
                    }, legacyGeneration = !ExecutionSemantics(environment.executionVersion).behaviorOnlyGeneration)
            } ?: ref.node.generation.resolve(library, ref.workflow, environment.executionVersion)
        } else null
        if (descriptor.usesModel && (profile == null || profile.model.isBlank())) {
            throw NodeExecutionFailure(ref, "请先选择并填写模型", RecoveryFailureKind.CONFIGURATION, retryable = false)
        }
        return InvocationPreparation(inputs.text, inputs.arguments, preview.messages, profile, historyRead, inputs.inputSourceIds)
    }
}

internal data class InjectionTarget(val workflowId: String, val nodeId: String, val blockId: String? = null) {
    val key: String get() = "$workflowId/$nodeId"
}

internal fun injectionTarget(main: MainCall, actions: Map<String, RegisteredAction>,
                             workflowId: String?, nodeId: String?, blockId: String? = null,
                             executionVersion: Int = ExecutionSemantics.CURRENT,
                             requireEnabled: Boolean = true): InjectionTarget? {
    val slotId = blockId?.trim()?.ifEmpty { null }
    if (slotId != null && !ExecutionSemantics(executionVersion).anchoredPromptSlots) return null
    fun target(workflow: Workflow, node: WorkflowNode): InjectionTarget? {
        if (slotId != null && node.blocks.none { it.id == slotId && it.injectionSlot && (!requireEnabled || it.enabled) }) return null
        return InjectionTarget(workflow.id, node.id, slotId)
    }
    val workflows = if (workflowId.isNullOrBlank()) main.workflows.filter { !requireEnabled || it.enabled }
        else main.workflows.filter { it.id == workflowId && (!requireEnabled || it.enabled) }
    if (!nodeId.isNullOrBlank()) {
        val matches = workflows.flatMap { workflow -> workflow.nodes.filter {
            it.id == nodeId && (!requireEnabled || it.enabled) && actions[it.actionId]?.descriptor?.usesModel == true
        }.mapNotNull { target(workflow, it) } }
        return matches.singleOrNull()
    }
    val workflow = if (!workflowId.isNullOrBlank()) workflows.singleOrNull()
        else if (main.primaryWorkflowId != null) workflows.firstOrNull { it.id == main.primaryWorkflowId }
        else workflows.firstOrNull()
    val node = workflow?.nodes?.lastOrNull { (!requireEnabled || it.enabled) && actions[it.actionId]?.descriptor?.usesModel == true }
    return node?.let { target(workflow, it) }
}
