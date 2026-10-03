package dev.gray.companion.core

import kotlinx.serialization.Serializable

@Serializable enum class RecoveryDecisionKind { RETRY, SKIP, MANUAL }
@Serializable data class RecoveryDecision(
    val kind: RecoveryDecisionKind,
    val nodeKey: String,
    val manualText: String? = null,
    val correction: String? = null
)

@Serializable enum class RecoveryFailureKind { MODEL_REQUEST, OUTPUT_FORMAT, CONFIGURATION, ACTION, PROMPT, CONFLICT }
@Serializable data class RecoveryFailure(
    val nodeKey: String,
    val workflowId: String,
    val nodeId: String,
    val title: String,
    val kind: RecoveryFailureKind,
    val reason: String,
    val source: String? = null,
    val retryable: Boolean = true,
    val provisional: Boolean = false
)

/** A successfully published node. Replaying this receipt never calls its handler again. */
@Serializable data class RecoveryReceipt(
    val nodeKey: String,
    val outcome: ActionOutcome,
    val reply: ChatMessage? = null,
    val publication: RecoveryPublication = RecoveryPublication()
)

@Serializable data class InjectionIdentity(val resourceId: String, val id: String)
@Serializable data class RecoveryPublication(
    val stateWrites: Map<String, String> = emptyMap(),
    val resultWrites: Map<String, String> = emptyMap(),
    val resultOrigins: Map<String, ResultOrigin> = emptyMap(),
    val macroAppends: Map<String, List<MacroVersion>> = emptyMap(),
    val injectionRemovals: List<InjectionIdentity> = emptyList(),
    val injectionAdds: List<PromptInjection> = emptyList()
)

/** Preparation is captured before a wave starts, so a retry uses that wave's old world. */
@Serializable data class RecoveryEnvironment(
    val id: String,
    val event: String,
    val session: Session,
    val input: String
)

@Serializable data class RecoveryPrepared(
    val nodeKey: String,
    val environmentId: String,
    val renderedInput: String,
    val arguments: Map<String, String>,
    val profile: ModelProfile? = null
)

/** The saved library contains only resources loaded by the captured call; it has no credentials. */
@Serializable data class RunRecovery(
    val id: String,
    val sessionId: String,
    val turnId: String,
    val input: String,
    val checkpoint: TurnCheckpoint,
    val initialSession: Session,
    val frozenResources: Library,
    val accepted: List<RecoveryReceipt> = emptyList(),
    val environments: List<RecoveryEnvironment> = emptyList(),
    val prepared: List<RecoveryPrepared> = emptyList(),
    val failures: List<RecoveryFailure> = emptyList(),
    val skippedKeys: Set<String> = emptySet(),
    val inactiveKeys: Set<String> = emptySet(),
    val admittedWorkflowIds: Set<String> = emptySet(),
    val declinedWorkflowIds: Set<String> = emptySet(),
    val unavailableKeys: Set<String> = emptySet(),
    val state: Map<String, String> = emptyMap(),
    val results: Map<String, String> = emptyMap(),
    val resultOrigins: Map<String, ResultOrigin> = emptyMap(),
    val macroHistory: Map<String, List<MacroVersion>> = emptyMap(),
    val promptInjections: List<PromptInjection> = emptyList(),
    val historyDigests: Map<String, HistoryDigest> = emptyMap(),
    val replies: List<ChatMessage> = emptyList(),
    val traces: List<RunTrace> = emptyList(),
    val regenerating: Boolean = false,
    val regenerationBase: Session? = null,
    val formatVersion: Int = 1,
    val executionVersion: Int = 1
) {
    val acceptedCount: Int get() = accepted.size
}
