package dev.gray.companion.core

/** Pure updates of the selected conversation and its small round restore records. */
data class MessageOperationResult(val session: Session? = null, val issues: List<Issue> = emptyList()) {
    val succeeded: Boolean get() = session != null && issues.none { it.error }
}

/** In-memory proof of a checked admission; the existing recovery journal persists its inputs. */
class RegenerationAdmission private constructor(
    internal val original: Session,
    internal val candidate: Session
) {
    companion object {
        internal fun checked(original: Session, candidate: Session) = RegenerationAdmission(original, candidate)
    }
}

data class MessageCapabilities(
    val canEditText: Boolean,
    val canDeleteText: Boolean,
    val canRegenerate: Boolean,
    val canSelectGenerationVariant: Boolean,
    val canFork: Boolean,
    val regenerateReason: String? = null,
    val forkReason: String? = null
)

object MessageOperations {
    /** A stopped round is complete as displayed; only its optional continuation journal is retired. */
    fun discardRecovery(session: Session): Session {
        val pending = session.pendingRun ?: return session
        return session.copy(pendingRun = null,
            unavailableResultKeys = if (pending.regenerating) session.unavailableResultKeys else pending.unavailableKeys)
    }

    /** Save accepted progress under one stable variant while the user round is unfinished. */
    fun recordPartialTurn(before: Session, result: RunResult): MessageOperationResult {
        val recovery = result.recovery ?: return unavailable("turn", "缺少恢复断点")
        if (result.status != RunStatus.PAUSED || recovery.regenerating || recovery.sessionId != before.id)
            return unavailable("turn", "恢复断点与会话不一致")
        val user = latestUser(before) ?: return unavailable("turn", "找不到当前用户消息")
        if (user.id != recovery.turnId || before.turnRecords.any { it.userMessageId == user.id })
            return unavailable("turn", "此轮已有记录或消息来源不一致")
        val checkpoint = recovery.checkpoint
        // The new captured definitions are additive; compare the same runtime boundary as old
        // journals without requiring the live caller to fabricate its captured resource world.
        if (checkpoint.copy(runtime = checkpoint.runtime.copy(completedWorld = null)) !=
            captureCheckpoint(before).let { it.copy(runtime = it.runtime.copy(completedWorld = null)) })
            return unavailable("turn", "恢复前检查点不一致")
        val variant = variantFrom(result, checkpoint)?.copy(id = recovery.id)
            ?: return unavailable("turn", "宏版本前缀与运行前记录不一致")
        val selected = variant.copy(replies = emptyList(), replyIds = result.replies.map { it.id })
        val record = TurnRecord(user.id, checkpoint, listOf(selected), selected.id)
        return MessageOperationResult(materialize(before, result).copy(
            messages = before.messages + result.replies,
            turnRecords = before.turnRecords + record,
            pendingRun = recovery,
            updatedAt = System.currentTimeMillis()
        ))
    }

    /** Replace the same unfinished variant; previously accepted reply IDs remain stable. */
    fun continuePartialTurn(current: Session, result: RunResult): MessageOperationResult {
        val pending = current.pendingRun ?: return unavailable("turn", "没有可继续的恢复断点")
        if (result.status !in setOf(RunStatus.PAUSED, RunStatus.SUCCESS) ||
            (result.recovery != null && result.recovery.id != pending.id))
            return unavailable("turn", "生成结果与恢复断点不一致")
        val user = latestUser(current) ?: return unavailable("turn", "找不到当前用户消息")
        val record = latestRecord(current, user.id)
            ?: return unavailable("turn", "恢复轮次记录不存在")
        if (pending.turnId != user.id || pending.sessionId != current.id ||
            record.selectedVariantId != pending.id || !record.restoreAllowed ||
            !selectedRoundHistoryKnown(current, record))
            return unavailable("turn", "恢复期间轮次或宏历史已变化")
        val variant = variantFrom(result, record.checkpoint)?.copy(id = pending.id)
            ?: return unavailable("turn", "宏版本前缀与恢复检查点不一致")
        val previousReplyIds = current.messages.filter { it.turnId == user.id && it.role == Role.ASSISTANT }.map { it.id }
        if (result.replies.map { it.id }.filter { it in previousReplyIds.toSet() } != previousReplyIds)
            return unavailable("turn", "已接受的回答身份发生变化")
        val userIndex = current.messages.indexOfLast { it.id == user.id }
        val selected = variant.copy(replies = emptyList(), replyIds = result.replies.map { it.id })
        return MessageOperationResult(materialize(current, result).copy(
            messages = current.messages.take(userIndex + 1) + result.replies,
            turnRecords = current.turnRecords.map { if (it.userMessageId == user.id) it.copy(
                variants = it.variants.map { old -> if (old.id == pending.id) selected else old }
            ) else it },
            pendingRun = result.recovery,
            updatedAt = System.currentTimeMillis()
        ))
    }

    fun capabilities(session: Session, messageId: String): MessageCapabilities {
        val index = session.messages.indexOfFirst { it.id == messageId }
        if (index < 0) return MessageCapabilities(false, false, false, false, false,
            "消息不存在", "消息不存在")
        val message = session.messages[index]
        val userId = if (message.role == Role.USER) message.id else message.turnId
        val record = session.turnRecords.firstOrNull { it.userMessageId == userId }
        val latest = latestUser(session)?.id == userId
        val canRestore = record?.restoreAllowed == true
        val userAlive = session.messages.firstOrNull { it.id == userId }?.deleted == false
        val prefixAvailable = record?.let { macroPrefix(session.macroHistory, it.checkpoint.macroLengths) != null } == true
        val knownHistory = record?.let { selectedRoundHistoryKnown(session, it) } == true
        val unfinished = message.role == Role.USER && userId != null && unfinishedLatestRound(session, userId)
        val canRegenerate = !message.deleted &&
            (if (latest) (canRestore && prefixAvailable && (record?.selectedVariantId == null || knownHistory)) || unfinished
                else canRestore && knownHistory) &&
            userAlive
        val regenerateReason = when {
            canRegenerate -> null
            message.deleted -> "消息已删除"
            record == null -> "此轮已有回答但没有运行状态检查点"
            !canRestore -> "手动修改过运行状态"
            !prefixAvailable || (record?.selectedVariantId != null && !knownHistory) -> "宏历史与此轮检查点不一致"
            else -> "用户消息已删除"
        }
        val canFork = index == session.messages.lastIndex ||
            (canRestore && (message.role == Role.USER ||
                (message.role == Role.ASSISTANT && knownHistory)))
        val forkReason = when {
            canFork -> null
            record == null -> "较早的消息没有运行状态检查点"
            !canRestore -> "手动修改过运行状态"
            else -> "宏历史与此轮检查点不一致"
        }
        val editable = !message.deleted && (!message.authoredOpening || canSelectOpening(session, messageId))
        return MessageCapabilities(editable, editable, canRegenerate,
            !message.deleted && userAlive && canRestore && knownHistory && record?.variants.orEmpty().size > 1, canFork,
            regenerateReason, forkReason)
    }

    fun captureRuntime(session: Session): RuntimeSnapshot = session.runtimeSnapshot()

    fun captureCheckpoint(session: Session): TurnCheckpoint = TurnCheckpoint(
        captureRuntime(session), session.macroHistory.mapValues { it.value.size }
    )

    /** Selected replies live in Session.messages; inactive replies live in their variant. */
    fun repliesForVariant(session: Session, record: TurnRecord, variant: TurnVariant,
        activeById: Map<String, ChatMessage>? = null): List<ChatMessage> {
        if (variant.id != record.selectedVariantId) return variant.replies
        val ids = variant.replyIds.ifEmpty { variant.replies.map { it.id } }
        if (ids.isEmpty()) return emptyList()
        val index = activeById ?: session.messages.associateBy { it.id }
        val active = ids.mapNotNull(index::get)
        return if (active.size == ids.size && active.all { it.turnId == record.userMessageId }) active
            else variant.replies
    }

    /** Call with the user already appended, before WorkflowEngine touches the round. */
    fun recordSuccessfulTurn(before: Session, result: RunResult): MessageOperationResult {
        if (result.status != RunStatus.SUCCESS) return unavailable("turn", "工作流未成功，不能保存生成候选")
        val user = latestUser(before) ?: return unavailable("turn", "找不到当前用户消息")
        if (before.turnRecords.any { it.userMessageId == user.id })
            return unavailable("turn", "此轮已有记录；重新生成请使用独立提交操作")
        val checkpoint = captureCheckpoint(before).let { it.copy(runtime = it.runtime.copy(
            completedWorld = CompletedWorlds.checkpointWorld(result.completedWorld))) }
        val variant = variantFrom(result, checkpoint) ?: return unavailable("turn", "宏版本前缀与运行前记录不一致")
        val selected = variant.copy(replies = emptyList(), replyIds = variant.replies.map { it.id })
        val record = TurnRecord(user.id, checkpoint, listOf(selected), selected.id)
        return MessageOperationResult(materialize(before, result).copy(
            messages = before.messages + result.replies,
            turnRecords = before.turnRecords + record,
            updatedAt = System.currentTimeMillis()
        ))
    }

    /** This candidate is in memory only. The persisted selected answer remains unchanged. */
    fun prepareRegeneration(session: Session, messageId: String): MessageOperationResult {
        val target = session.messages.firstOrNull { it.id == messageId && !it.deleted }
            ?: return unavailable("message.$messageId", "消息不存在或已删除")
        val userMessageId = if (target.role == Role.USER) target.id else target.turnId
            ?: return unavailable("message.$messageId", "这条回复没有用户轮次来源")
        val record = latestRecord(session, userMessageId)
            ?: return if (target.role == Role.USER && unfinishedLatestRound(session, userMessageId))
                MessageOperationResult(session)
            else unavailable("message.$messageId", "只支持最新轮次，且已有回答须有运行状态检查点")
        if (!record.restoreAllowed) return unavailable("message.$messageId", "手动修改过运行状态，此轮不能恢复")
        if (record.selectedVariantId != null && !selectedRoundHistoryKnown(session, record))
            return unavailable("message.$messageId", "宏历史与此轮检查点不一致")
        val userIndex = session.messages.indexOfLast { it.id == record.userMessageId }
        if (userIndex < 0 || session.messages[userIndex].deleted)
            return unavailable("message.$messageId", "用户消息已删除，不能重新生成")
        val prefix = macroPrefix(session.macroHistory, record.checkpoint.macroLengths)
            ?: return unavailable("message.$messageId", "当前宏历史缺少该轮的恢复前缀")
        return MessageOperationResult(restore(session.copy(
            messages = session.messages.take(userIndex + 1), macroHistory = prefix
        ), record.checkpoint.runtime))
    }

    /** Historical candidates keep their conversation identity and archive their continuation. */
    fun regenerationBase(session: Session, messageId: String): MessageOperationResult =
        ConversationPaths.regenerationBase(session, messageId)

    /** Fork through a complete historical round with the chosen variant's recorded world. */
    fun forkAtVariant(session: Session, turnId: String, variantId: String): MessageOperationResult {
        val userIndex = session.messages.indexOfFirst {
            it.id == turnId && it.role == Role.USER && it.outputSlot == OutputSlot.BODY
        }
        if (userIndex < 0 || session.messages[userIndex].deleted)
            return unavailable("turn.$turnId", "用户消息不存在或已删除")
        val record = session.turnRecords.firstOrNull { it.userMessageId == turnId }
            ?: return unavailable("turn.$turnId", "这一轮没有运行状态检查点")
        if (!record.restoreAllowed) return unavailable("turn.$turnId", "手动修改过运行状态，不能从此处恢复")
        val chosen = record.variants.firstOrNull { it.id == variantId }
            ?: return unavailable("turn.$turnId", "生成候选不存在")
        if (!selectedRoundHistoryKnown(session, record))
            return unavailable("turn.$turnId", "当前宏历史与所选轮次记录不一致")
        val prefix = macroPrefix(session.macroHistory, record.checkpoint.macroLengths)
            ?: return unavailable("turn.$turnId", "当前宏历史缺少此轮检查点前缀")
        val replies = repliesForVariant(session, record, chosen)
        val variants = materializeSelectedReplies(session, record).map { variant ->
            if (variant.id == chosen.id) variant.copy(replies = emptyList(), replyIds = replies.map { it.id })
            else variant
        }
        val selected = record.copy(variants = variants, selectedVariantId = chosen.id)
        return MessageOperationResult(restore(session, chosen.after).copy(
            id = newId(), isPinned = false,
            messages = session.messages.take(userIndex + 1) + replies,
            macroHistory = mergeMacros(prefix, chosen.macroAppends),
            turnRecords = session.turnRecords.takeWhile { it.userMessageId != turnId } + selected,
            updatedAt = System.currentTimeMillis()
        ))
    }

    /** Separate the immutable restore boundary from the policy approved before execution. */
    fun admitRegeneration(original: Session, candidate: Session, live: Library): RegenerationAdmission {
        val user = latestUser(original) ?: throw IllegalArgumentException("找不到当前用户消息")
        val prepared = prepareRegeneration(original, user.id).session
            ?: throw IllegalArgumentException("重新生成恢复边界无效")
        val expected = CompletedWorlds.withExtensionPolicyForNewTurn(live, prepared)
        require(candidate == expected) { "重新生成候选与准入世界不一致" }
        return RegenerationAdmission.checked(original, candidate)
    }

    /** Recovery uses its saved admission, never today's switches or resource definitions. */
    fun restoreRegenerationAdmission(recovery: RunRecovery): RegenerationAdmission {
        val original = recovery.regenerationBase
            ?: throw IllegalArgumentException("重新生成断点缺少原始恢复边界")
        val candidate = recovery.initialSession
        require(recovery.regenerating && recovery.sessionId == original.id && candidate.id == original.id &&
            latestUser(original)?.id == recovery.turnId && latestUser(candidate)?.id == recovery.turnId) {
            "重新生成断点来源不一致"
        }
        val prepared = prepareRegeneration(original, recovery.turnId).session
            ?: throw IllegalArgumentException("重新生成恢复边界无效")
        require(candidate.copy(executableWorld = prepared.executableWorld) == prepared &&
            CompletedWorlds.isExtensionPolicyOverlay(prepared.executableWorld, candidate.executableWorld)) {
            "重新生成断点与获准执行世界不一致"
        }
        return RegenerationAdmission.checked(original, candidate)
    }

    /** Commit a successful candidate once, carrying the old answer's variants forward. */
    fun commitRegeneration(original: Session, candidate: Session, result: RunResult,
                           admission: RegenerationAdmission? = null): MessageOperationResult {
        if (result.status != RunStatus.SUCCESS) return unavailable("turn", "工作流未成功，原回答已保留")
        if (admission != null && (original != admission.original || candidate != admission.candidate))
            return unavailable("turn", "重新生成准入后会话或执行世界已改变")
        val user = latestUser(original) ?: return unavailable("turn", "找不到当前用户消息")
        val record = latestRecord(original, user.id)
        if (record == null) {
            if (!unfinishedLatestRound(original, user.id) || candidate.id != original.id ||
                candidate.messages != original.messages || candidate.macroHistory != original.macroHistory ||
                captureRuntime(candidate) != captureRuntime(admission?.candidate ?: original) || candidate.turnRecords != original.turnRecords)
                return unavailable("turn", "未完成轮次的候选与当前会话不一致")
            return recordSuccessfulTurn(candidate, result)
        }
        if (!record.restoreAllowed || candidate.id != original.id || latestUser(candidate)?.id != user.id)
            return unavailable("turn", "重新生成期间会话或恢复边界已改变")
        val expected = admission?.candidate ?: prepareRegeneration(original, user.id).session
        if (expected == null || candidate.messages != expected.messages || candidate.macroHistory != expected.macroHistory ||
            captureRuntime(candidate) != captureRuntime(expected))
            return unavailable("turn", "重新生成候选与当前会话不一致")
        val variant = variantFrom(result, record.checkpoint) ?: return unavailable("turn", "宏版本前缀与检查点不一致")
        val updated = record.copy(variants = materializeSelectedReplies(original, record) +
            variant.copy(replies = emptyList(), replyIds = variant.replies.map { it.id }),
            selectedVariantId = variant.id)
        val messages = candidate.messages + result.replies
        return MessageOperationResult(materialize(candidate, result).copy(
            messages = messages,
            turnRecords = original.turnRecords.map { if (it.userMessageId == user.id) updated else it },
            updatedAt = System.currentTimeMillis()
        ))
    }

    fun applyLatestVariant(session: Session, turnId: String, variantId: String): MessageOperationResult {
        val record = latestRecord(session, turnId) ?: return unavailable("turn.$turnId", "只支持切换最新用户轮次")
        if (!record.restoreAllowed) return unavailable("turn.$turnId", "手动修改过运行状态，此轮不能恢复")
        val variant = record.variants.firstOrNull { it.id == variantId }
            ?: return unavailable("turn.$turnId", "生成候选不存在")
        if (record.selectedVariantId == variantId) return MessageOperationResult(session)
        val replies = repliesForVariant(session, record, variant)
        val prefix = macroPrefix(session.macroHistory, record.checkpoint.macroLengths)
            ?: return unavailable("turn.$turnId", "当前宏历史缺少该轮的恢复前缀")
        val userIndex = session.messages.indexOfLast { it.id == turnId }
        if (userIndex < 0) return unavailable("turn.$turnId", "用户消息不存在")
        val restored = restore(session, variant.after)
        return MessageOperationResult(restored.copy(
            messages = session.messages.take(userIndex + 1) + replies,
            macroHistory = mergeMacros(prefix, variant.macroAppends),
            turnRecords = session.turnRecords.map {
                if (it.userMessageId == turnId) it.copy(
                    variants = materializeSelectedReplies(session, record).map { item ->
                        if (item.id == variantId) item.copy(replies = emptyList(), replyIds = replies.map { reply -> reply.id })
                        else item
                    },
                    selectedVariantId = variantId
                ) else it
            },
            updatedAt = System.currentTimeMillis()
        ))
    }

    /** Delete a whole generation candidate, including its runtime effects. */
    fun deleteGenerationVariant(session: Session, turnId: String, variantId: String): MessageOperationResult {
        val record = session.turnRecords.firstOrNull { it.userMessageId == turnId }
            ?: return unavailable("turn.$turnId", "生成轮次不存在")
        val removedIndex = record.variants.indexOfFirst { it.id == variantId }
        if (removedIndex < 0) return unavailable("turn.$turnId", "生成候选不存在")
        val remaining = record.variants.filterNot { it.id == variantId }
        if (record.selectedVariantId != variantId) return MessageOperationResult(session.copy(
            turnRecords = session.turnRecords.map {
                if (it.userMessageId == turnId) it.copy(variants = remaining) else it
            }, updatedAt = System.currentTimeMillis()
        ))
        if (latestUser(session)?.id != turnId) {
            val fork = forkAtVariant(session, turnId, variantId)
            return fork.session?.let { deleteGenerationVariant(it, turnId, variantId) } ?: fork
        }
        if (!record.restoreAllowed) return unavailable("turn.$turnId", "手动修改过运行状态，此轮不能恢复")
        if (remaining.isNotEmpty()) {
            val next = remaining[(removedIndex - 1).coerceAtLeast(0).coerceAtMost(remaining.lastIndex)]
            val without = session.copy(turnRecords = session.turnRecords.map {
                if (it.userMessageId == turnId) it.copy(variants = remaining) else it
            })
            return applyLatestVariant(without, turnId, next.id)
        }
        val prefix = macroPrefix(session.macroHistory, record.checkpoint.macroLengths)
            ?: return unavailable("turn.$turnId", "当前宏历史缺少该轮的恢复前缀")
        val userIndex = session.messages.indexOfLast { it.id == turnId }
        if (userIndex < 0) return unavailable("turn.$turnId", "用户消息不存在")
        val tombstones = session.messages.drop(userIndex + 1).map { reply -> reply.copy(
            text = "", textRevisions = emptyList(), activeTextRevisionId = null,
            activeTextRevisionCreatedAt = null, deleted = true, bodyDisplay = null
        ) }
        return MessageOperationResult(restore(session, record.checkpoint.runtime).copy(
            messages = session.messages.take(userIndex + 1) + tombstones,
            macroHistory = prefix,
            turnRecords = session.turnRecords.map {
                if (it.userMessageId == turnId) it.copy(variants = emptyList(), selectedVariantId = null) else it
            }, updatedAt = System.currentTimeMillis()
        ))
    }

    fun editText(session: Session, messageId: String, text: String): MessageOperationResult = changeText(session, messageId) { current ->
        if (current.text == text) current else current.copy(
            text = text,
            textRevisions = current.textRevisions + TextRevision(
                current.activeTextRevisionId ?: current.id, current.text,
                current.activeTextRevisionCreatedAt ?: current.createdAt, current.bodyDisplay
            ),
            activeTextRevisionId = newId(), activeTextRevisionCreatedAt = nextRevisionTime(current), bodyDisplay = null
        )
    }

    fun canSelectOpening(session: Session, messageId: String): Boolean =
        session.messages.none { it.role == Role.USER } &&
            session.messages.any { it.id == messageId && it.authoredOpening && !it.deleted }

    fun selectOpeningRevision(session: Session, messageId: String, revisionId: String): MessageOperationResult =
        if (!canSelectOpening(session, messageId)) unavailable("message.$messageId", "对话开始后请新建聊天选择开场白")
        else selectTextRevision(session, messageId, revisionId)

    fun selectTextRevision(session: Session, messageId: String, revisionId: String): MessageOperationResult =
        if (session.messages.any { it.id == messageId && it.authoredOpening } && !canSelectOpening(session, messageId))
            unavailable("message.$messageId", "对话开始后请新建聊天选择开场白")
        else changeText(session, messageId) { current ->
            if ((current.activeTextRevisionId ?: current.id) == revisionId) current else {
                val chosen = current.textRevisions.firstOrNull { it.id == revisionId } ?: return@changeText null
                current.copy(
                    text = chosen.text,
                    textRevisions = current.textRevisions.filterNot { it.id == revisionId } + TextRevision(
                        current.activeTextRevisionId ?: current.id, current.text,
                        current.activeTextRevisionCreatedAt ?: current.createdAt, current.bodyDisplay
                    ),
                    activeTextRevisionId = chosen.id, activeTextRevisionCreatedAt = chosen.createdAt, bodyDisplay = chosen.bodyDisplay
                )
            }
        }

    /** Delete the selected text candidate; the final deletion leaves a logical tombstone. */
    fun deleteMessage(session: Session, messageId: String): MessageOperationResult =
        if (session.pendingRun?.let { !it.regenerating && it.turnId == messageId } == true)
            rollbackPendingUserTurn(session, messageId)
        else changeText(session, messageId) { current ->
        val replacement = current.textRevisions.maxWithOrNull(compareBy<TextRevision> { it.createdAt }.thenBy { it.id })
        if (replacement == null) current.copy(
            text = "", textRevisions = emptyList(), activeTextRevisionId = null,
            activeTextRevisionCreatedAt = null, deleted = true, bodyDisplay = null
        ) else current.copy(
            text = replacement.text,
            textRevisions = current.textRevisions.filterNot { it.id == replacement.id },
            activeTextRevisionId = replacement.id, activeTextRevisionCreatedAt = replacement.createdAt, bodyDisplay = replacement.bodyDisplay
        )
    }

    /** Remove only the USER message that opened a paused round, restoring its complete before-world. */
    fun rollbackPendingUserTurn(session: Session, messageId: String): MessageOperationResult {
        val pending = session.pendingRun ?: return unavailable("message.$messageId", "没有可撤回的断点")
        if (pending.regenerating || pending.sessionId != session.id || pending.turnId != messageId)
            return unavailable("message.$messageId", "只能撤回触发本轮的用户消息")
        val initial = pending.initialSession
        val userIndex = initial.messages.lastIndex
        val user = initial.messages.lastOrNull()
        if (initial.id != session.id || user == null || user.id != messageId || user.role != Role.USER ||
            user.outputSlot != OutputSlot.BODY || user.deleted ||
            session.messages.getOrNull(userIndex) != user ||
            session.messages.take(userIndex) != initial.messages.take(userIndex) ||
            session.messages.drop(userIndex + 1).any { it.role != Role.ASSISTANT || it.turnId != messageId })
            return unavailable("message.$messageId", "触发消息或本轮消息来源已改变")
        val record = session.turnRecords.lastOrNull()
        if (record == null || record.userMessageId != messageId || !record.restoreAllowed ||
            record.selectedVariantId != pending.id || record.checkpoint != pending.checkpoint ||
            session.turnRecords.dropLast(1) != initial.turnRecords || !selectedRoundHistoryKnown(session, record))
            return unavailable("message.$messageId", "本轮检查点或宏历史已改变")
        val prefix = macroPrefix(session.macroHistory, record.checkpoint.macroLengths)
            ?: return unavailable("message.$messageId", "宏历史缺少发送前的版本")
        val before = pending.beforeUserSession ?: run {
            // An older journal has no pre-send Session. Its prior selected candidate may prove
            // the original pinned world, even though new-turn policy replaced the admitted one.
            val beforeWorld = initial.executableWorld?.let { admitted ->
                initial.turnRecords.lastOrNull()?.let { prior ->
                    val selected = prior.variants.firstOrNull { it.id == prior.selectedVariantId }
                    val previousUser = initial.messages.dropLast(1).lastOrNull {
                        it.role == Role.USER && it.outputSlot == OutputSlot.BODY
                    }
                    selected?.after?.completedWorld?.takeIf { candidate ->
                        prior.restoreAllowed && previousUser?.id == prior.userMessageId &&
                            selectedRoundHistoryKnown(initial, prior) &&
                            selected.replyIds.all { replyId -> initial.messages.dropLast(1).any {
                                it.id == replyId && it.turnId == prior.userMessageId
                            } } &&
                            selected.after.copy(completedWorld = null) ==
                                pending.checkpoint.runtime.copy(completedWorld = null) &&
                            admitted.choices == candidate.choices
                    }
                }
            }
            if ((initial.executableWorld != null && beforeWorld == null) ||
                initial.runtimeSnapshot().copy(completedWorld = null) != pending.checkpoint.runtime.copy(completedWorld = null))
                return unavailable("message.$messageId", "旧断点缺少完整的发送前快照，无法安全撤回")
            initial.withRuntime(pending.checkpoint.runtime.copy(completedWorld = beforeWorld)).copy(
                messages = initial.messages.dropLast(1), macroHistory = prefix, executableWorld = beforeWorld
            )
        }
        if (before.id != session.id || before.pendingRun != null ||
            before.messages + user != initial.messages || before.turnRecords != initial.turnRecords ||
            before.macroHistory.filterValues { it.isNotEmpty() } != prefix.filterValues { it.isNotEmpty() } ||
            session.macroHistory.filterValues { it.isNotEmpty() } !=
                mergeMacros(prefix, record.variants.first { it.id == pending.id }.macroAppends).filterValues { it.isNotEmpty() })
            return unavailable("message.$messageId", "发送前快照与当前轮次不一致")
        return MessageOperationResult(before.copy(updatedAt = System.currentTimeMillis()))
    }

    fun invalidateRestore(session: Session): Session = session.copy(
        turnRecords = session.turnRecords.map { it.copy(restoreAllowed = false) }
    )

    /** A prior fork must have a real boundary, and always keeps the IDs of retained messages. */
    fun forkAt(session: Session, messageId: String): MessageOperationResult {
        val index = session.messages.indexOfFirst { it.id == messageId }
        if (index < 0) return unavailable("message.$messageId", "消息不存在")
        if (index == session.messages.lastIndex) return MessageOperationResult(session.copy(
            id = newId(), isPinned = false, updatedAt = System.currentTimeMillis()
        ))
        val message = session.messages[index]
        val record = session.turnRecords.firstOrNull { it.userMessageId == (if (message.role == Role.USER) message.id else message.turnId) }
            ?: return unavailable("message.$messageId", "较早的消息没有运行状态检查点，不能安全复制")
        if (!record.restoreAllowed) return unavailable("message.$messageId", "手动修改过运行状态，不能从此处复制")
        if (message.role == Role.USER) {
            val prefix = macroPrefix(session.macroHistory, record.checkpoint.macroLengths)
                ?: return unavailable("message.$messageId", "宏历史缺少复制所需的前缀")
            return MessageOperationResult(restore(session, record.checkpoint.runtime).copy(
                id = newId(), isPinned = false, messages = session.messages.take(index + 1),
                macroHistory = prefix,
                turnRecords = session.turnRecords.takeWhile { it.userMessageId != record.userMessageId },
                updatedAt = System.currentTimeMillis()
            ))
        }
        val selectedId = record.selectedVariantId ?: return unavailable("message.$messageId", "所选生成候选不存在")
        // Outputs from one workflow round share a world commit, including the other display regions.
        return forkAtVariant(session, record.userMessageId, selectedId)
    }

    private fun changeText(session: Session, messageId: String, change: (ChatMessage) -> ChatMessage?): MessageOperationResult {
        val index = session.messages.indexOfFirst { it.id == messageId }
        if (index < 0) return unavailable("message.$messageId", "消息不存在")
        val current = session.messages[index]
        if (current.deleted) return unavailable("message.$messageId", "消息已删除")
        if (current.authoredOpening && !canSelectOpening(session, messageId))
            return unavailable("message.$messageId", "对话开始后请新建聊天选择开场白")
        val changed = change(current) ?: return unavailable("message.$messageId", "文本候选不存在")
        if (changed == current) return MessageOperationResult(session)
        val messages = session.messages.toMutableList().also { it[index] = changed }
        val records = session.turnRecords.map { record ->
            record.copy(
                checkpoint = record.checkpoint.copy(runtime = record.checkpoint.runtime.copy(historyDigests = emptyMap())),
                variants = record.variants.map { variant ->
                    variant.copy(
                        after = variant.after.copy(historyDigests = emptyMap())
                    )
                }
            )
        }
        return MessageOperationResult(session.copy(
            messages = messages, historyDigests = emptyMap(), turnRecords = records,
            updatedAt = System.currentTimeMillis()
        ))
    }

    private fun latestUser(session: Session): ChatMessage? = session.messages.lastOrNull {
        it.role == Role.USER && it.outputSlot == OutputSlot.BODY
    }

    private fun unfinishedLatestRound(session: Session, userId: String): Boolean {
        if (latestUser(session)?.id != userId || session.turnRecords.any { it.userMessageId == userId }) return false
        val index = session.messages.indexOfLast { it.id == userId && it.role == Role.USER }
        return index >= 0 && !session.messages[index].deleted &&
            session.messages.drop(index + 1).none { it.role == Role.ASSISTANT && !it.deleted }
    }

    private fun nextRevisionTime(message: ChatMessage): Long = maxOf(
        System.currentTimeMillis(),
        (listOf(message.activeTextRevisionCreatedAt ?: message.createdAt) + message.textRevisions.map { it.createdAt })
            .maxOrNull()!!.let { if (it == Long.MAX_VALUE) it else it + 1 }
    )

    private fun latestRecord(session: Session, userMessageId: String): TurnRecord? {
        if (latestUser(session)?.id != userMessageId) return null
        return session.turnRecords.firstOrNull { it.userMessageId == userMessageId }
    }

    private fun materializeSelectedReplies(session: Session, record: TurnRecord): List<TurnVariant> =
        record.variants.map { variant ->
            if (variant.id == record.selectedVariantId) variant.copy(
                replies = repliesForVariant(session, record, variant), replyIds = emptyList()
            ) else variant
        }

    /** A checkpoint contains lengths, so verify its active suffix against known committed data. */
    private fun selectedRoundHistoryKnown(session: Session, record: TurnRecord): Boolean {
        if (macroPrefix(session.macroHistory, record.checkpoint.macroLengths) == null) return false
        val selected = record.variants.firstOrNull { it.id == record.selectedVariantId } ?: return false
        return selected.macroAppends.all { (address, suffix) ->
            val versions = session.macroHistory[address].orEmpty()
            val start = record.checkpoint.macroLengths[address] ?: 0
            versions.size >= start + suffix.size && versions.subList(start, start + suffix.size) == suffix
        }
    }

    private fun variantFrom(result: RunResult, checkpoint: TurnCheckpoint): TurnVariant? {
        val suffix = mutableMapOf<String, List<MacroVersion>>()
        result.macroHistory.forEach { (address, values) ->
            val count = checkpoint.macroLengths[address] ?: 0
            if (count > values.size) return null
            if (values.size > count) suffix[address] = values.drop(count)
        }
        if (checkpoint.macroLengths.any { (address, count) -> result.macroHistory[address].orEmpty().size < count }) return null
        return TurnVariant(replies = result.replies, after = CompletedWorlds.compact(result.runtimeSnapshot()), macroAppends = suffix)
    }

    private fun macroPrefix(history: Map<String, List<MacroVersion>>, lengths: Map<String, Int>): Map<String, List<MacroVersion>>? {
        if (lengths.any { (address, count) -> count < 0 || history[address].orEmpty().size < count }) return null
        return lengths.mapValues { (address, count) -> history[address].orEmpty().take(count) }
    }

    private fun mergeMacros(prefix: Map<String, List<MacroVersion>>, appends: Map<String, List<MacroVersion>>): Map<String, List<MacroVersion>> =
        (prefix.keys + appends.keys).associateWith { address -> prefix[address].orEmpty() + appends[address].orEmpty() }

    private fun materialize(session: Session, result: RunResult): Session =
        session.withRuntime(result.runtimeSnapshot()).copy(macroHistory = result.macroHistory,
            executableWorld = if (session.executableWorld != null) result.completedWorld ?: session.executableWorld else null)

    private fun restore(session: Session, runtime: RuntimeSnapshot): Session = CompletedWorlds.restore(session, runtime)

    private fun unavailable(path: String, message: String) = MessageOperationResult(issues = listOf(Issue(path, message)))
}
