package dev.gray.companion.core

/** Apply only to a new execution's captures. Never normalizes the user's existing archive. */
object BodyOnlyArchives {
    /** Display metadata is independent of body-only request and macro history. */
    fun displayReasoning(snapshot: ReasoningSnapshot?, executionVersion: Int): ReasoningSnapshot? =
        if (ExecutionSemantics(executionVersion).persistDisplayReasoning) snapshot?.copy(nativeMessage = null)
        else reasoning(snapshot, executionVersion)

    fun reasoning(snapshot: ReasoningSnapshot?, executionVersion: Int): ReasoningSnapshot? =
        snapshot.takeUnless { ExecutionSemantics(executionVersion).bodyOnlyArchives }

    fun fullResponse(text: String?, executionVersion: Int): String? =
        text.takeUnless { ExecutionSemantics(executionVersion).bodyOnlyArchives }

    /** The response has already passed through the reasoning parser; do not parse its BODY twice. */
    fun response(parsed: ModelResponse, executionVersion: Int): ModelResponse =
        parsed.copy(reasoning = displayReasoning(parsed.reasoning, executionVersion))

    fun message(message: ChatMessage, executionVersion: Int): ChatMessage =
        if (ExecutionSemantics(executionVersion).bodyOnlyArchives && message.reasoningSnapshot != null)
            message.copy(reasoningSnapshot = null) else message

    fun macro(version: MacroVersion, executionVersion: Int): MacroVersion =
        if (ExecutionSemantics(executionVersion).bodyOnlyArchives &&
            (version.fullResponseText != null || version.reasoningSnapshot != null))
            version.copy(fullResponseText = null, reasoningSnapshot = null) else version

    internal fun candidate(outcome: ActionOutcome, executionVersion: Int, rawModelText: String? = null,
        fullResponseText: String? = null, reasoningSnapshot: ReasoningSnapshot? = null,
        provenanceNote: String? = null): ValidatedCandidate = ValidatedCandidate(outcome, rawModelText,
            fullResponse(fullResponseText, executionVersion), displayReasoning(reasoningSnapshot, executionVersion), provenanceNote)
}
