package dev.gray.companion.core

/** Final text request projection. Source floors and their metadata remain untouched. */
object ModelRequestMessages {
    fun prepare(messages: List<ChatMessage>, executionVersion: Int = ExecutionSemantics.CURRENT): List<ChatMessage> {
        val semantics = ExecutionSemantics(executionVersion)
        if (!semantics.mergeAdjacentModelRoles) return messages
        // Providers omit blank text-only requests; they cannot act as Role boundaries on the wire.
        val nonblank = messages.filter { it.text.isNotBlank() }
        if (nonblank.size < 2) return nonblank
        return buildList {
            var start = 0
            while (start < nonblank.size) {
                val first = nonblank[start]
                var end = start + 1
                while (end < nonblank.size && nonblank[end].role == first.role &&
                    (!semantics.perBlockMessageMerging ||
                        (nonblank[end - 1].mergeAdjacentMessages && nonblank[end].mergeAdjacentMessages))) end++
                add(if (end == start + 1) first else first.copy(
                    text = nonblank.subList(start, end).joinToString("\n") { it.text },
                    turnId = null, speakerName = null, textRevisions = emptyList(),
                    activeTextRevisionId = null, activeTextRevisionCreatedAt = null,
                    workflowId = null, nodeId = null, rawText = null, bodyDisplay = null,
                    reasoningSnapshot = null, authoredOpening = false
                ))
                start = end
            }
        }
    }
}
