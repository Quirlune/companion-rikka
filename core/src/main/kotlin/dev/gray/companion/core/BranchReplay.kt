package dev.gray.companion.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Deterministic pure reconstruction. Never executes an ActionHandler, publication or model. */
object BranchReplay {
    private val noModel = object : ModelGateway {
        override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String =
            error("分支纯重算不能请求模型")
    }

    suspend fun rebuild(session: Session, executor: PluginCodeExecutor? = null): MessageOperationResult {
        val world = session.executableWorld ?: return MessageOperationResult(session)
        try {
            CompletedWorlds.requireValidWorld(world)
            val environmentMap = world.environments.associateBy { it.id }
            val values = linkedMapOf<String, String>()
            var total = 0
            for (recipe in world.recipes) {
                currentCoroutineContext().ensureActive()
                val needed = recipe.targets.filter { it.key in session.unavailableResultKeys || it.key !in session.results }
                if (needed.isEmpty()) continue
                require(recipe.canonicalInput.length <= CompletedWorlds.MAX_RECIPE_CHARS) { "重算输入超过限额" }
                val environment = environmentMap[recipe.environmentId] ?: error("分支缺少原始重算环境")
                val resources = ExecutionResources.forWorkflow(world.library, recipe.workflowId, world.executionVersion)
                val workflow = when (recipe.resourceId) {
                    world.choices.mainCallId -> resources.mainCalls.firstOrNull { it.id == recipe.resourceId }?.configuredWorkflows()
                    else -> resources.groups.firstOrNull { it.id == recipe.resourceId }?.workflows
                }?.firstOrNull { it.id == recipe.workflowId } ?: error("分支缺少原始调用定义")
                val node = workflow.nodes.firstOrNull { it.id == recipe.nodeId } ?: error("分支缺少原始行为定义")
                require(recipe.targets.all { it.origin.resourceId == recipe.resourceId && it.origin.workflowId == workflow.id && it.origin.nodeId == node.id }) {
                    "分支重算目标来源不一致"
                }
                val allowed = if (recipe.kind == PureReplayKind.REGEX_COPY) setOf(node.regexOutputCopy.outputKey)
                    else setOf(node.outputKey) + node.regexOutputCopy.outputKey.takeIf { node.regexOutputCopy.enabled }.let { listOfNotNull(it) }
                require(recipe.targets.all { it.key in allowed }) { "分支重算只能重建自身纯结果" }
                val context = ActionContext(node, recipe.canonicalInput, environment.session.messages,
                    recipe.arguments, environment.session.state, environment.session.results, null, noModel, {},
                    session = environment.session, library = resources, workflowId = workflow.id,
                    executionVersion = world.executionVersion)
                val outcome = when (recipe.kind) {
                    PureReplayKind.REGEX_COPY -> {
                        require(node.regexOutputCopy.enabled) { "原始正则副本配置缺失" }
                        ActionOutcome(macroWrites = mapOf("result.${node.regexOutputCopy.outputKey}" to
                            RegexOutputCopies.transform(node.regexOutputCopy, recipe.canonicalInput)))
                    }
                    else -> {
                        val raw = when (recipe.kind) {
                            PureReplayKind.TEXT_RENDER, PureReplayKind.TEXT_EXTRACT, PureReplayKind.CARD_DEFINE -> knownOutcome(recipe.kind, context)
                            PureReplayKind.PLUGIN_TEXT -> {
                                require(node.actionId == "plugin.run")
                                val plugin = requireNotNull(recipe.pluginContext) { "分支缺少捕获的插件代码与输入" }
                                require(plugin.code.version == 1) { "宿主能力插件不能纯重算" }
                                val original = resources.extensions.firstOrNull { it.id == plugin.pluginId }?.code
                                require(original == plugin.code && plugin.input == recipe.canonicalInput) { "分支插件代码或输入不一致" }
                                PluginCodes.requireContext(plugin)
                                ActionOutcome(PluginCodes.requireOutput(requireNotNull(executor) { "缺少隔离插件重算执行器" }.execute(plugin)))
                            }
                            else -> error("此行为不是纯重算配方")
                        }
                        require(!raw.hasEffects()) { "纯重算不能产生副作用" }
                        val action = RegisteredAction(ActionDescriptor(node.actionId, node.title), ActionHandler { error("不执行重算handler") })
                        OutcomeValidator(resources, environment.session).validate(PlannedNode(workflow, node, action), context, raw)
                    }
                }
                require(outcome.stateWrites.isEmpty() && outcome.injectionWrites.isEmpty() && outcome.activationWrites.isEmpty() &&
                    outcome.emittedEvents.isEmpty() && outcome.historyWrites == HistoryMemoryPatch()) { "纯重算不能重放副作用" }
                for (target in needed) {
                    val text = if (target.key == node.outputKey && recipe.kind != PureReplayKind.REGEX_COPY) outcome.text
                        else outcome.macroWrites["result.${target.key}"] ?: error("重算未产生目标结果")
                    require(CompletedWorlds.fingerprint(text) == target.fingerprint) { "旧环境重算与已接受结果不一致" }
                    total += text.length
                    require(total <= CompletedWorlds.MAX_CAPTURE_CHARS) { "分支重算输出超过限额" }
                    values[target.key] = text
                }
            }
            return MessageOperationResult(session.copy(results = session.results + values,
                resultOrigins = session.resultOrigins + world.recipes.flatMap { it.targets }.filter { it.key in values }.associate { it.key to it.origin },
                unavailableResultKeys = session.unavailableResultKeys - values.keys))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            return MessageOperationResult(issues = listOf(Issue("branch.replay", "旧分支纯结果无法安全重建；未读取当前定义或请求模型，请保留原分支")))
        }
    }

    internal fun knownOutcome(kind: PureReplayKind, context: ActionContext): ActionOutcome = when (kind) {
        PureReplayKind.TEXT_RENDER -> { require(context.node.actionId == "text.render"); ActionOutcome(context.input) }
        PureReplayKind.TEXT_EXTRACT -> {
            require(context.node.actionId == "text.extract")
            ActionOutcome(RegexOutputCopies.transform(RegexOutputCopy(pattern = context.arguments.getValue("pattern"),
                captureGroup = context.arguments.getValue("captureGroup").toInt(),
                mode = RegexTransformMode.valueOf(context.arguments.getValue("mode")),
                replacement = context.arguments["replacement"].orEmpty()), context.input))
        }
        PureReplayKind.CARD_DEFINE -> { require(context.node.actionId == "card.define"); CardDisplays.define(context) }
        else -> error("此行为不能同步验证纯配方")
    }
}
