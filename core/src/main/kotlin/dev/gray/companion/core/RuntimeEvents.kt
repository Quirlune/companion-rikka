package dev.gray.companion.core

/** Runtime-owned lifecycle events. Custom event names use the same scheduler. */
object RuntimeEvents {
    const val HISTORY_PROCESS = "history.process"
    const val TURN_PREPARE = "turn.prepare"
    const val USER_MESSAGE = "user.message"
    const val REPLY_COMPLETED = "reply.completed"
    const val WORKFLOW_COMPLETED_PREFIX = "workflow.completed:"

    fun workflowCompleted(workflowId: String): String = WORKFLOW_COMPLETED_PREFIX + workflowId

    val reserved: Set<String> = setOf(HISTORY_PROCESS, TURN_PREPARE, USER_MESSAGE, REPLY_COMPLETED)
    fun isReserved(event: String): Boolean = event in reserved || event.startsWith(WORKFLOW_COMPLETED_PREFIX)
}

fun Workflow.effectiveTrigger(): String = afterWorkflowId?.takeIf { it.isNotBlank() }
    ?.let(RuntimeEvents::workflowCompleted) ?: trigger
