package dev.gray.companion.ui

import dev.gray.companion.core.*

internal fun callBehaviorTypeLabel(id: String, actions: List<ActionDescriptor>): String = when (id) {
    "model.generate" -> "调用模型"
    "prompt.inject" -> "提示词注入"
    "prompt.remove" -> "移除提示词注入"
    "macro.process" -> "宏处理"
    "user.macro.create" -> "创建用户宏"
    "history.view.copy" -> "历史视图副本"
    else -> actions.firstOrNull { it.id == id }?.title ?: "未注册行为（$id）"
}

internal fun editableCallBehaviorTypes(actions: List<ActionDescriptor>): List<ActionDescriptor> =
    actions.filter { (it.presentation == ActionPresentation.STANDARD && it.id != "card.define") || it.id == "prompt.remove" }
        .sortedBy { when (it.id) { "model.generate" -> 0; "prompt.inject" -> 1; "prompt.remove" -> 2; else -> 3 } }

/** Type changes are draft edits; keep identity, references and retained configuration for compatibility. */
internal fun changeCallBehaviorType(node: WorkflowNode, action: ActionDescriptor, actions: List<ActionDescriptor>): WorkflowNode {
    if (node.actionId == action.id) return node
    val oldLabel = callBehaviorTypeLabel(node.actionId, actions)
    val defaultTitle = node.title in setOf("生成", "节点", "行为", oldLabel, actions.firstOrNull { it.id == node.actionId }?.title)
    val arguments = if (action.id.startsWith("prompt.") && node.arguments["id"].isNullOrBlank())
        node.arguments + ("id" to node.id) else node.arguments
    return node.copy(actionId = action.id,
        title = if (defaultTitle) callBehaviorTypeLabel(action.id, actions) else node.title,
        arguments = arguments, visible = false,
        recordTrajectory = action.usesModel,
        includeHistory = if (action.usesModel) node.includeHistory else false,
        inputTemplate = if (action.id == "prompt.inject" && node.inputTemplate == "{{input}}") "" else node.inputTemplate)
}

internal fun newCallBehavior(action: ActionDescriptor, nodes: List<WorkflowNode>, usedKeys: Set<String>, actions: List<ActionDescriptor>): WorkflowNode {
    val key = nextCallOutputKey(usedKeys + nodes.map { it.outputKey }, "step")
    val node = WorkflowNode(title = nextDisplayName(nodes.map { it.title }, callBehaviorTypeLabel(action.id, actions)),
        actionId = action.id, outputKey = key, slug = nextEditorSlug(nodes.map { it.slug }, "output"),
        visible = false, includeHistory = action.usesModel, inputTemplate = if (action.id == "prompt.inject") "" else "{{input}}", recordTrajectory = action.usesModel)
    return if (action.id.startsWith("prompt.")) node.copy(arguments = mapOf("id" to node.id)) else node
}

internal fun callBehaviorSummary(node: WorkflowNode, references: List<PromptReferenceChoice>, library: Library?): String {
    val source = references.firstOrNull { it.macro.trim() == node.inputTemplate.trim() }?.let { it.title }
        ?: when { node.inputTemplate.isBlank() -> "尚未选择来源"; "{{" in node.inputTemplate -> "自定义宏内容"; else -> "自定义文字" }
    if (node.actionId == "prompt.inject") {
        val callId = node.arguments["targetWorkflowId"].orEmpty()
        val nodeId = node.arguments["targetNodeId"].orEmpty()
        val main = library?.mainCalls?.firstOrNull { main -> main.workflows.any { call ->
            if (callId.isNotBlank()) call.id == callId else nodeId.isNotBlank() && call.nodes.any { it.id == nodeId } } }
            ?: library?.sessions?.firstOrNull { it.id == library.selectedSessionId }?.mainCallId?.let { id -> library.mainCalls.firstOrNull { it.id == id } }
        val call = main?.workflows?.firstOrNull { it.id == callId }
            ?: main?.workflows?.firstOrNull { it.id == main.primaryWorkflowId }
        val target = if (nodeId.isNotBlank()) main?.workflows?.flatMap { it.nodes }?.firstOrNull { it.id == nodeId }
            else call?.nodes?.lastOrNull { it.enabled && it.actionId == "model.generate" }
        val slotId = node.arguments["targetBlockId"].orEmpty()
        val slot = target?.blocks?.firstOrNull { it.id == slotId }?.title
            ?: if (slotId.isBlank()) "普通注入" else "待定位的接收槽"
        return "来源：$source\n目标：${main?.name ?: "运行时主调用"} → ${target?.title ?: "模型行为"} → $slot"
    }
    return when (node.actionId) {
        "prompt.remove" -> "移除指定的已注入内容"
        "model.generate" -> "${node.blocks.size} 条提示词 → 模型结果"
        "user.macro.create" -> node.userMacro?.let { "${it.shortcut} + 空格 → 发送时展开" } ?: "登记输入快捷词，使用时才取值"
        else -> "来源：$source → 行为结果"
    }
}
