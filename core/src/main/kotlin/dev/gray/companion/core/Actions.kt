package dev.gray.companion.core

import java.math.BigDecimal
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable enum class ActionFieldType { STRING, NUMBER, BOOLEAN, TEMPLATE, MACRO }
@Serializable enum class ActionPresentation { STANDARD, COMPATIBILITY }
/**
 * PURE_MODEL handlers may only request a model and return candidate text. They must tolerate
 * concurrent attempts and cancellation, and must not perform other external effects. The engine
 * checks returned effects, but cannot undo or detect a handler's arbitrary external operations.
 */
@Serializable enum class ActionExecution { HANDLER, PURE_MODEL }
@Serializable data class ActionChoice(val value: String, val title: String)
@Serializable data class ActionFieldDescriptor(val key: String, val title: String, val type: ActionFieldType = ActionFieldType.STRING, val required: Boolean = false, val defaultValue: String? = null, val choices: List<ActionChoice> = emptyList(), val integer: Boolean = false)
@Serializable data class ActionDescriptor(
    val id: String,
    val title: String,
    val description: String = "",
    val fields: List<ActionFieldDescriptor> = emptyList(),
    val usesModel: Boolean = false,
    @Transient val stateWriteKeyField: String? = null,
    @Transient val macroWriteKeyField: String? = null,
    @Transient val injectionIdField: String? = null,
    @Transient val injectionTargetWorkflowField: String? = null,
    @Transient val injectionTargetNodeField: String? = null,
    @Transient val removesInjection: Boolean = false,
    val presentation: ActionPresentation = ActionPresentation.STANDARD,
    val execution: ActionExecution = ActionExecution.HANDLER
)

data class ActionContext(
    val node: WorkflowNode,
    val input: String,
    val messages: List<ChatMessage>,
    val arguments: Map<String, String>,
    val state: Map<String, String>,
    val results: Map<String, String>,
    val profile: ModelProfile?,
    val gateway: ModelGateway,
    val onDelta: suspend (String) -> Unit,
    val dependencyOutputs: Map<String, ActionOutcome> = emptyMap(),
    val session: Session? = null,
    val library: Library? = null,
    val workflowId: String = "",
    val executionVersion: Int = 6,
    val macroTarget: MacroTargetSnapshot? = null
)

@Serializable data class PromptInjectionPatch(
    val id: String, val block: PromptBlock? = null,
    val targetWorkflowId: String? = null, val targetNodeId: String? = null,
    val scope: InjectionScope = InjectionScope.TURN
)

@Serializable data class ActionOutcome(
    val text: String = "", val stateWrites: Map<String, String> = emptyMap(),
    val emittedEvents: List<String> = emptyList(),
    val macroWrites: Map<String, String> = emptyMap(),
    val activationWrites: Map<String, Boolean> = emptyMap(),
    val injectionWrites: List<PromptInjectionPatch> = emptyList(),
    val historyWrites: HistoryMemoryPatch = HistoryMemoryPatch()
)
fun interface ActionHandler { suspend fun execute(context: ActionContext): ActionOutcome }
data class RegisteredAction(val descriptor: ActionDescriptor, val handler: ActionHandler)

class ActionRegistry {
    private val actions = linkedMapOf<String, RegisteredAction>()

    @Synchronized fun register(descriptor: ActionDescriptor, handler: ActionHandler): ActionRegistry {
        require(descriptor.id.isNotBlank()) { "动作 ID 不能为空" }
        require(descriptor.fields.map { it.key }.distinct().size == descriptor.fields.size) { "动作参数键重复" }
        descriptor.executionIssue()?.let { throw IllegalArgumentException(it) }
        actions[descriptor.id] = RegisteredAction(descriptor.copy(fields = descriptor.fields.toList()), handler)
        return this
    }
    fun register(id: String, handler: ActionHandler): ActionRegistry = register(ActionDescriptor(id, id), handler)
    @Synchronized fun handler(id: String): ActionHandler? = actions[id]?.handler
    @Synchronized fun descriptors(): List<ActionDescriptor> = actions.values.map { it.descriptor.copy(fields = it.descriptor.fields.toList()) }
    @Synchronized fun snapshot(): Map<String, RegisteredAction> = actions.toMap()

    companion object {
        fun defaults() = ActionRegistry()
            .register(ActionDescriptor("model.generate", "模型生成", "使用所选模型生成文本", usesModel = true,
                execution = ActionExecution.PURE_MODEL)) { context ->
                val profile = context.profile ?: error("未选择模型")
                ActionOutcome(context.gateway.generateAttempt(profile, context.messages, context.onDelta))
            }
            .register(ActionDescriptor("text.render", "组合文本", "无需模型，将输入模板中的宏展开为新的调用宏；不创建界面")) { context -> ActionOutcome(context.input) }
            .register(ActionDescriptor("state.set", "设置状态", "写入会话状态", listOf(
                ActionFieldDescriptor("key", "状态键", required = true),
                ActionFieldDescriptor("value", "值", ActionFieldType.TEMPLATE)
            ), stateWriteKeyField = "key", presentation = ActionPresentation.COMPATIBILITY)) { context ->
                val key = context.arguments.getValue("key").trim()
                val value = context.arguments["value"] ?: context.input
                ActionOutcome(value, mapOf(key to value))
            }
            .register(ActionDescriptor("state.increment", "递增状态", "将数值状态增减指定数量，可设置上下限", listOf(
                ActionFieldDescriptor("key", "状态键", required = true),
                ActionFieldDescriptor("amount", "变化量", ActionFieldType.NUMBER, required = true, defaultValue = "1"),
                ActionFieldDescriptor("min", "下限", ActionFieldType.NUMBER),
                ActionFieldDescriptor("max", "上限", ActionFieldType.NUMBER)
            ), stateWriteKeyField = "key", presentation = ActionPresentation.COMPATIBILITY)) { context ->
                val key = context.arguments.getValue("key").trim()
                val current = context.state[key]?.toBigDecimal() ?: BigDecimal.ZERO
                val amount = context.arguments.getValue("amount").toBigDecimal()
                val minimum = context.arguments["min"]?.takeIf { it.isNotBlank() }?.toBigDecimal()
                val maximum = context.arguments["max"]?.takeIf { it.isNotBlank() }?.toBigDecimal()
                require(minimum == null || maximum == null || minimum <= maximum)
                var next = current + amount
                if (minimum != null && next < minimum) next = minimum
                if (maximum != null && next > maximum) next = maximum
                val value = next.stripTrailingZeros().toPlainString()
                ActionOutcome(value, mapOf(key to value))
            }
            .register(ActionDescriptor("event.emit", "触发事件", "派发自定义事件", listOf(
                ActionFieldDescriptor("event", "事件名称", required = true)
            ))) { context ->
                val event = context.arguments.getValue("event").trim()
                require(!(if (context.executionVersion == 1) event in RuntimeEvents.reserved else RuntimeEvents.isReserved(event))) { "此事件由运行时派发" }
                ActionOutcome(context.input, emittedEvents = listOf(event))
            }
            .register(ActionDescriptor("macro.write", "更新宏", "发布宏的新版本", listOf(
                ActionFieldDescriptor("key", "宏地址", ActionFieldType.MACRO, required = true),
                ActionFieldDescriptor("value", "内容", ActionFieldType.TEMPLATE)
            ), macroWriteKeyField = "key", presentation = ActionPresentation.COMPATIBILITY)) { context ->
                val key = MacroReferences.canonicalAddress(context.arguments.getValue("key")) ?: error("宏地址无效")
                val value = context.arguments["value"] ?: context.input
                ActionOutcome(value, macroWrites = mapOf(key to value))
            }
            .register(ActionDescriptor("macro.activate", "启用或停用宏", "发布宏的可见性版本", listOf(
                ActionFieldDescriptor("key", "宏地址", ActionFieldType.MACRO, required = true),
                ActionFieldDescriptor("enabled", "启用", ActionFieldType.BOOLEAN, required = true)
            ), macroWriteKeyField = "key", presentation = ActionPresentation.COMPATIBILITY)) { context ->
                val key = MacroReferences.canonicalAddress(context.arguments.getValue("key")) ?: error("宏地址无效")
                val enabled = context.arguments.getValue("enabled").toBooleanStrict()
                ActionOutcome(context.input, activationWrites = mapOf(key to enabled))
            }
            .register(ActionDescriptor("macro.process", "处理宏", "选择或创建目标宏并按步骤处理内容", listOf(
                ActionFieldDescriptor("key", "宏地址", ActionFieldType.MACRO)
            ), macroWriteKeyField = "key")) { context ->
                val config = context.node.macroProcess
                val address = canonicalWriteAddress(config.key.ifBlank { context.arguments["key"].orEmpty() })
                    ?: error("宏地址无效")
                val session = requireNotNull(context.session)
                val library = requireNotNull(context.library)
                val current = MacroReferences.currentVersion(library, session, address)
                val capturedTarget = context.macroTarget?.takeIf { context.executionVersion >= 3 }
                require(capturedTarget == null || capturedTarget.address == address) { "捕获的宏目标与当前配置不一致" }
                val existing = when {
                    capturedTarget != null -> capturedTarget.text
                    address in session.currentOnlyMacros && current == null &&
                        (address.startsWith("result.") || address.startsWith("state.")) -> null
                    address.startsWith("state.") -> context.state[address.substring(6)]
                    current != null -> current.text
                    else -> MacroReferences.baseValue(library, session, address)
                }
                if (config.mode == MacroProcessMode.ENABLE || config.mode == MacroProcessMode.DISABLE) {
                    return@register ActionOutcome(existing.orEmpty(), activationWrites =
                        mapOf(address to (config.mode == MacroProcessMode.ENABLE)))
                }
                val base = when (config.mode) {
                    MacroProcessMode.SET -> context.input
                    MacroProcessMode.UPDATE -> existing ?: MacroResolver(library, session, context.input, context.executionVersion).render(config.initial)
                    MacroProcessMode.COPY -> readCopySource(library, session, config.source, capturedTarget)
                        ?: error("源宏或指定版本不存在")
                    else -> error("处理模式无效")
                }
                val resolver = MacroResolver(library, session, context.input, context.executionVersion)
                val value = TextProcessing.apply(config.steps, base, context.state, context.results) { resolver.read(it) }
                if (address.startsWith("state.")) ActionOutcome(value, stateWrites = mapOf(address.substring(6) to value))
                else ActionOutcome(value, macroWrites = mapOf(address to value))
            }
            .register(ActionDescriptor("text.extract", "正则副本", "提取或替换输入，将处理结果保存为独立输出宏", listOf(
                ActionFieldDescriptor("pattern", "表达式", required = true),
                ActionFieldDescriptor("captureGroup", "捕获组", ActionFieldType.NUMBER, defaultValue = "0", integer = true),
                ActionFieldDescriptor("mode", "模式", defaultValue = "EXTRACT", choices = listOf(
                    ActionChoice("EXTRACT", "提取"), ActionChoice("REPLACE", "替换"))),
                ActionFieldDescriptor("replacement", "替换内容")
            ))) { context ->
                if (context.executionVersion == 1) return@register ActionOutcome(SafePatterns.extract(
                    context.arguments.getValue("pattern"), context.input, context.arguments.getValue("captureGroup").toInt()).orEmpty())
                ActionOutcome(RegexOutputCopies.transform(RegexOutputCopy(pattern = context.arguments.getValue("pattern"),
                    captureGroup = context.arguments.getValue("captureGroup").toInt(),
                    mode = RegexTransformMode.valueOf(context.arguments.getValue("mode")),
                    replacement = context.arguments["replacement"].orEmpty()), context.input))
            }
            .register(ActionDescriptor("history.memory.write", "记录分段记忆", "保存摘要与真实原文楼层的映射", listOf(
                ActionFieldDescriptor("source", "History 来源", required = true, defaultValue = "{{history}}"),
                ActionFieldDescriptor("title", "记忆标题", defaultValue = "记忆")
            ))) { context ->
                val session = requireNotNull(context.session)
                val selection = HistoryViews.selection(context.arguments.getValue("source"), context.executionVersion) ?: error("History 来源引用无效")
                val main = requireNotNull(context.library).mainCalls.first { it.id == session.mainCallId }
                val segment = HistoryMemory.segmentFromSources(context.arguments["title"].orEmpty(), context.input,
                    HistoryViews.sources(session, selection, main), context.workflowId)
                ActionOutcome(segment.id, historyWrites = HistoryMemoryPatch(segments = listOf(segment)))
            }
            .register(ActionDescriptor("history.view.copy", "History 压缩或展开副本", "通过记忆 ID 创建独立历史视图，不复制原文", listOf(
                ActionFieldDescriptor("source", "History 来源", required = true, defaultValue = "{{history}}"),
                ActionFieldDescriptor("targetId", "副本英文标识", required = true),
                ActionFieldDescriptor("name", "副本名称"),
                ActionFieldDescriptor("mode", "模式", defaultValue = "COMPRESS", choices = listOf(
                    ActionChoice("COMPRESS", "压缩"), ActionChoice("EXPAND", "展开"))),
                ActionFieldDescriptor("ids", "记忆 ID", ActionFieldType.TEMPLATE),
                ActionFieldDescriptor("pattern", "ID 提取正则"),
                ActionFieldDescriptor("captureGroup", "捕获组", ActionFieldType.NUMBER, defaultValue = "0", integer = true)
            ))) { context ->
                val selection = HistoryViews.selection(context.arguments.getValue("source"), context.executionVersion) ?: error("History 来源引用无效")
                val ids = HistoryActions.selectedIds(context.arguments["ids"].orEmpty(),
                    context.arguments["pattern"].orEmpty(), context.arguments.getValue("captureGroup").toInt())
                val target = context.arguments.getValue("targetId").trim()
                val session = requireNotNull(context.session)
                val main = requireNotNull(context.library).mainCalls.first { it.id == session.mainCallId }
                val patch = HistoryViews.copy(session, selection, target,
                    context.arguments["name"].orEmpty(), HistorySegmentMode.valueOf(context.arguments.getValue("mode")), ids, main)
                ActionOutcome(target, historyWrites = patch)
            }
            .register(ActionDescriptor("prompt.inject", "注入提示词", "向主调用目标模型节点注入提示块", listOf(
                ActionFieldDescriptor("id", "注入标识", required = true),
                ActionFieldDescriptor("role", "角色", defaultValue = Role.SYSTEM.name, choices = listOf(
                    ActionChoice("SYSTEM", "系统"), ActionChoice("USER", "用户"), ActionChoice("ASSISTANT", "助手"))),
                ActionFieldDescriptor("position", "位置", defaultValue = PromptPosition.BEFORE_HISTORY.name, choices = listOf(
                    ActionChoice("BEFORE_HISTORY", "历史前"), ActionChoice("IN_HISTORY", "历史中"),
                    ActionChoice("AFTER_HISTORY", "历史后"))),
                ActionFieldDescriptor("depth", "历史深度", ActionFieldType.NUMBER, defaultValue = "0", integer = true),
                ActionFieldDescriptor("targetWorkflowId", "目标调用"),
                ActionFieldDescriptor("targetNodeId", "目标节点"),
                ActionFieldDescriptor("emptyBehavior", "空内容处理", defaultValue = "ALLOW", choices = listOf(
                    ActionChoice("ALLOW", "照常注入"), ActionChoice("SKIP", "跳过"), ActionChoice("REMOVE", "移除"))),
                ActionFieldDescriptor("scope", "生命周期", defaultValue = InjectionScope.TURN.name, choices = listOf(
                    ActionChoice("TURN", "当前轮"), ActionChoice("SESSION", "整个会话")))
            ), injectionIdField = "id", injectionTargetWorkflowField = "targetWorkflowId", injectionTargetNodeField = "targetNodeId")) { context ->
                val id = context.arguments.getValue("id").trim()
                val role = Role.valueOf(context.arguments.getValue("role").trim().uppercase())
                val position = PromptPosition.valueOf(context.arguments.getValue("position").trim().uppercase())
                val depth = context.arguments.getValue("depth").toInt().also { require(it >= 0) }
                val scope = when (context.arguments.getValue("scope").trim().uppercase()) {
                    "CURRENT", "TURN" -> InjectionScope.TURN
                    "SESSION" -> InjectionScope.SESSION
                    else -> error("注入生命周期无效")
                }
                if (context.input.isEmpty()) when (context.arguments["emptyBehavior"]?.trim()?.uppercase()) {
                    "SKIP" -> return@register ActionOutcome(context.input)
                    "REMOVE" -> return@register ActionOutcome(context.input,
                        injectionWrites = listOf(PromptInjectionPatch(id)))
                }
                val block = PromptBlock(id = id, template = context.input, role = role, position = position, depth = depth)
                ActionOutcome(context.input, injectionWrites = listOf(PromptInjectionPatch(id, block,
                    context.arguments["targetWorkflowId"]?.trim()?.ifEmpty { null },
                    context.arguments["targetNodeId"]?.trim()?.ifEmpty { null }, scope)))
            }
            .register(ActionDescriptor("prompt.remove", "移除注入", "按所有者与标识移除提示块", listOf(
                ActionFieldDescriptor("id", "注入标识", required = true)
            ), injectionIdField = "id", removesInjection = true, presentation = ActionPresentation.COMPATIBILITY)) { context ->
                ActionOutcome(context.input, injectionWrites = listOf(PromptInjectionPatch(context.arguments.getValue("id").trim())))
            }
    }
}

internal fun ActionDescriptor.executionIssue(): String? {
    if (execution != ActionExecution.PURE_MODEL) return null
    if (!usesModel) return "纯模型动作必须声明 usesModel"
    if (stateWriteKeyField != null || macroWriteKeyField != null || injectionIdField != null ||
        injectionTargetWorkflowField != null || injectionTargetNodeField != null || removesInjection)
        return "纯模型动作不能声明状态、宏或提示词注入写入"
    return null
}

internal fun ActionOutcome.hasEffects(): Boolean = stateWrites.isNotEmpty() || emittedEvents.isNotEmpty() ||
    macroWrites.isNotEmpty() || activationWrites.isNotEmpty() || injectionWrites.isNotEmpty() || !historyWrites.isEmpty()

private fun readCopySource(library: Library, session: Session, source: String, target: MacroTargetSnapshot? = null): String? {
    val raw = source.trim().removeSurrounding("{{", "}}").trim()
    val address = canonicalWriteAddress(raw.substringBefore('@')) ?: error("源宏地址无效")
    val selector = raw.substringAfter('@', "latest")
    require(validVersionSelector(selector)) { "源宏版本选择无效" }
    if (selector == "latest" && target?.address == address) return target.text.takeIf { target.enabled }
    val version = MacroReferences.selectVersion(library, session, address, selector)
    if (selector == "latest" && version == null && address in session.currentOnlyMacros &&
        (address.startsWith("result.") || address.startsWith("state."))) return null
    if (address.startsWith("state.") && selector == "latest")
        return session.state[address.substring(6)]?.takeIf { version?.enabled != false }
    if (version != null) return version.text.takeIf { version.enabled }
    if (selector != "latest") return null
    return when {
        address.startsWith("state.") -> session.state[address.substring(6)]
        else -> MacroReferences.baseValue(library, session, address)
    }
}

internal fun ActionDescriptor.validate(arguments: Map<String, String>, path: String): List<Issue> = buildList {
    for (field in fields) {
        val value = arguments[field.key] ?: field.defaultValue
        if (field.required && value.isNullOrBlank()) {
            add(Issue("$path.argument.${field.key}", "缺少必填参数：${field.title}"))
            continue
        }
        if (value.isNullOrBlank()) continue
        if (!value.contains("{{") && field.choices.isNotEmpty() && field.choices.none { it.value == value }) {
            add(Issue("$path.argument.${field.key}", "参数选项无效：${field.title}"))
        }
        if (!value.contains("{{") && field.integer && value.toIntOrNull() == null) {
            add(Issue("$path.argument.${field.key}", "参数必须是整数：${field.title}"))
            continue
        }
        when (field.type) {
            ActionFieldType.NUMBER -> if (!value.contains("{{") && value.toBigDecimalOrNull() == null) add(Issue("$path.argument.${field.key}", "参数必须是数字：${field.title}"))
            ActionFieldType.BOOLEAN -> if (!value.contains("{{") && value !in setOf("true", "false")) add(Issue("$path.argument.${field.key}", "参数必须是 true 或 false：${field.title}"))
            ActionFieldType.MACRO -> if (MacroReferences.canonicalAddress(value) == null) add(Issue("$path.argument.${field.key}", "宏地址无效：${field.title}"))
            else -> Unit
        }
    }
}
internal fun ActionDescriptor.withDefaults(arguments: Map<String, String>): Map<String, String> =
    fields.mapNotNull { field -> field.defaultValue?.let { field.key to it } }.toMap() + arguments
