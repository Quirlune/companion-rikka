package dev.gray.companion.core

/** Special model defaults are distinct from ordinary multi-node calls. */
object DefaultCallRules {
    fun primary(main: MainCall): Workflow? = main.workflows.firstOrNull { it.id == main.primaryWorkflowId }
        ?: main.workflows.firstOrNull().takeIf { main.primaryWorkflowId == null }
    fun compatible(workflow: Workflow?): Boolean = workflow?.nodes?.singleOrNull()?.let {
        it.actionId == "model.generate" && it.visible && it.outputSlot == OutputSlot.BODY && it.dependsOn.isEmpty()
    } == true
    fun issues(main: MainCall): List<Issue> {
        if (!main.specialDefault) return emptyList()
        val path = "main.${main.id}.primaryWorkflowId"
        val call = primary(main) ?: return listOf(Issue(path, "特殊默认调用不存在"))
        val node = call.nodes.singleOrNull()
            ?: return listOf(Issue(path, "特殊默认调用固定一个模型主体；其他行为请放入普通调用"))
        return buildList {
            if (node.actionId != "model.generate") add(Issue(path, "特殊默认调用的行为固定为调用模型"))
            if (!node.visible || node.outputSlot != OutputSlot.BODY) add(Issue(path, "特殊默认调用的结果必须作为正文"))
            if (node.dependsOn.isNotEmpty()) add(Issue(path, "特殊默认调用的模型主体不能依赖同调用中的其他节点"))
        }
    }
}
