package dev.gray.companion.core

internal data class PlannedNode(val workflow: Workflow, val node: WorkflowNode, val action: RegisteredAction?) {
    val key: String get() = "${workflow.id}/${node.id}"
    val priority: Int get() = if (workflow.queued) workflow.priority else 0
}

internal data class EventPlan(val trigger: String, val waves: List<List<PlannedNode>>)

internal data class RunPlan(val events: Map<String, EventPlan>) {
    companion object {
        fun create(workflows: List<Workflow>, availableActions: Map<String, RegisteredAction>): Pair<RunPlan?, Issue?> {
            val events = linkedMapOf<String, EventPlan>()
            val categories = mutableMapOf<String, String>()
            for (workflow in workflows.filter { it.enabled }) for (node in workflow.nodes.filter { it.enabled }) {
                val target = node.declaredWriteAddress(availableActions[node.actionId]?.descriptor)
                    ?.takeIf { it.startsWith("result.") }?.substringAfter("result.")
                for (key in listOfNotNull(node.outputKey, target).distinct()) {
                    val previous = categories.putIfAbsent(key, node.resultCategory)
                    if (previous != null && previous != node.resultCategory)
                        return null to Issue("workflow.${workflow.id}.node.${node.id}", "同一个输出宏须使用相同分类")
                }
            }
            for ((trigger, eventWorkflows) in workflows.filter { it.enabled }.groupBy { it.trigger }) {
                val nodes = mutableListOf<PlannedNode>()
                for (workflow in eventWorkflows) {
                    for (node in workflow.nodes) {
                        val action = availableActions[node.actionId]
                        if (node.enabled && action == null) return null to Issue("workflow.${workflow.id}.node.${node.id}", "未注册的动作：${node.actionId}")
                        if (node.enabled) {
                            action!!.descriptor.executionIssue()?.let {
                                return null to Issue("workflow.${workflow.id}.node.${node.id}", it)
                            }
                            val issue = action.descriptor.validate(node.arguments, "workflow.${workflow.id}.node.${node.id}").firstOrNull { it.error }
                            if (issue != null) return null to issue
                            if (node.actionId == "event.emit" && node.arguments["event"]?.trim() in RuntimeEvents.reserved) {
                                return null to Issue("workflow.${workflow.id}.node.${node.id}.argument.event", "此事件由运行时派发")
                            }
                        }
                        nodes += PlannedNode(workflow, node, action)
                    }
                }
                val pending = nodes.toMutableList()
                val waves = mutableListOf<List<PlannedNode>>()
                val planned = mutableSetOf<String>()
                val unconditional = mutableSetOf<String>()
                while (pending.isNotEmpty()) {
                    val priority = pending.minOf { it.priority }
                    val ready = pending.filter { ref -> ref.priority == priority &&
                        ref.node.dependsOn.all { "${ref.workflow.id}/$it" in planned } }
                    if (ready.isEmpty()) return null to Issue("event.$trigger", "工作流依赖无法完成")
                    // A conditional or disabled ancestor can skip its entire dependent path.
                    val guaranteed = ready.filter { ref ->
                        ref.workflow.condition == null && ref.workflow.recognition == null &&
                            ref.node.enabled && ref.node.condition == null && ref.node.recognition == null &&
                            ref.node.dependsOn.all { "${ref.workflow.id}/$it" in unconditional }
                    }
                    val macroKeys = guaranteed.flatMap { ref ->
                        buildSet {
                            add("result.${ref.node.outputKey}")
                            ref.node.declaredStateWriteAddress(ref.action?.descriptor)?.let(::add)
                            ref.node.declaredWriteAddress(ref.action?.descriptor)?.let(::add)
                        }
                    }
                    if (macroKeys.size != macroKeys.toSet().size) {
                        return null to Issue("event.$trigger", "并行节点写入同一宏地址")
                    }
                    waves += ready
                    planned += ready.map { it.key }
                    unconditional += guaranteed.map { it.key }
                    pending.removeAll(ready.toSet())
                }
                events[trigger] = EventPlan(trigger, waves)
            }
            return RunPlan(events.toMap()) to null
        }
    }
}
