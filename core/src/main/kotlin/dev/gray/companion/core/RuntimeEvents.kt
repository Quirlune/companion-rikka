package dev.gray.companion.core

/** Runtime-owned lifecycle events. Custom event names use the same scheduler. */
object RuntimeEvents {
    const val HISTORY_PROCESS = "history.process"
    const val TURN_PREPARE = "turn.prepare"
    const val USER_MESSAGE = "user.message"
    const val REPLY_COMPLETED = "reply.completed"

    val reserved: Set<String> = setOf(HISTORY_PROCESS, TURN_PREPARE, USER_MESSAGE, REPLY_COMPLETED)
    fun isReserved(event: String): Boolean = event in reserved
}
