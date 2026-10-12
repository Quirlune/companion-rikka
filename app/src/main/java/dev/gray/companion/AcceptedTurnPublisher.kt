package dev.gray.companion

import dev.gray.companion.core.Library
import dev.gray.companion.core.LibraryRepository
import dev.gray.companion.core.MessageOperationResult
import dev.gray.companion.core.MessageOperations
import dev.gray.companion.core.RegenerationAdmission
import dev.gray.companion.core.OutputSlot
import dev.gray.companion.core.RunResult
import dev.gray.companion.core.RunStatus
import dev.gray.companion.core.Session
import dev.gray.companion.core.runtimeSnapshot
import dev.gray.companion.core.withRuntime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Commits accepted receipts and their chat projection under the controller's admission mutex. */
internal class AcceptedTurnPublisher(
    private val repository: LibraryRepository,
    private val mutex: Mutex,
    private val library: MutableStateFlow<Library>,
    private val state: MutableStateFlow<ControllerState>,
    private val streaming: MutableStateFlow<String>,
    private val session: Session,
    private val sourceSessionId: String,
    private val regenerationBase: Session?,
    private val beforeUserSession: Session?,
    recoveryId: String?,
    private val regenerationAdmission: RegenerationAdmission? = null,
) {
    private var persistedRunId = recoveryId
    private var lastPersistedResult: RunResult? = null

    suspend fun persist(result: RunResult) = withContext(NonCancellable) {
        mutex.withLock {
            if (result == lastPersistedResult) return@withLock
            val current = library.value.sessions.firstOrNull { it.id == sourceSessionId }
                ?: error("运行中的会话已移除")
            val journal = result.recovery?.let { recovery -> recovery.copy(
                regenerating = regenerationBase != null, regenerationBase = regenerationBase,
                beforeUserSession = recovery.beforeUserSession ?: beforeUserSession
            ) }
            if (journal != null) {
                if (persistedRunId == null) persistedRunId = journal.id
                val currentPendingRun = current.pendingRun
                check(persistedRunId == journal.id && (currentPendingRun == null || currentPendingRun.id == journal.id)) {
                    "运行断点已改变"
                }
            }
            val savedResult = result.copy(recovery = journal)
            val forking = session.id != sourceSessionId
            val operation = when {
                regenerationBase != null && result.status != RunStatus.SUCCESS ->
                    MessageOperationResult(current.copy(pendingRun = journal ?: current.pendingRun))
                regenerationBase != null -> MessageOperations.commitRegeneration(
                    regenerationBase, session, savedResult, regenerationAdmission)
                current.pendingRun != null -> MessageOperations.continuePartialTurn(current, savedResult)
                result.status == RunStatus.SUCCESS -> MessageOperations.recordSuccessfulTurn(session, savedResult)
                journal != null -> MessageOperations.recordPartialTurn(session, savedResult)
                else -> MessageOperationResult(current)
            }
            val generated = operation.session ?: error(operation.issues.firstOrNull()?.message ?: "运行进度无法保存")
            val committed = generated.copy(title = current.title, titlePending = current.titlePending,
                folderId = current.folderId,
                isPinned = if (forking && result.status == RunStatus.SUCCESS) false else current.isPinned,
                updatedAt = System.currentTimeMillis())
            val next = if (forking && result.status == RunStatus.SUCCESS) library.value.copy(
                sessions = library.value.sessions.map { if (it.id == sourceSessionId) it.copy(pendingRun = null) else it } + committed,
                selectedSessionId = committed.id)
                else library.value.copy(sessions = library.value.sessions.map { if (it.id == sourceSessionId) committed else it })
            repository.save(next)
            library.value = next
            lastPersistedResult = result
            if (regenerationBase != null && result.status != RunStatus.SUCCESS && journal != null) {
                state.value = state.value.copy(generationPreview = session.withRuntime(journal.runtimeSnapshot()).copy(
                    messages = session.messages + journal.replies, macroHistory = journal.macroHistory))
            }
            val acceptedNodes = journal?.accepted.orEmpty().map { it.reply?.let { reply -> reply.workflowId to reply.nodeId } }.toSet()
            val pending = state.value.pendingOutputs.filterNot { (it.workflowId to it.nodeId) in acceptedNodes }
            streaming.value = pending.filter { it.outputSlot == OutputSlot.BODY }.joinToString("\n\n") { it.text }
            state.value = state.value.copy(pendingOutputs = pending, streaming = streaming.value)
        }
    }
}
