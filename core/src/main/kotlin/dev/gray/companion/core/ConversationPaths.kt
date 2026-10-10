package dev.gray.companion.core

/** Session is the active projection; only inactive continuations are archived here. */
object ConversationPaths {
    private data class Choice(val userId: String, val revisionId: String, val variantId: String?)

    /** Structural checks for imported paths; no historical data is rewritten or guessed. */
    fun validate(session: Session): List<Issue> = buildList {
        if (session.inactivePaths.map { it.id }.distinct().size != session.inactivePaths.size)
            add(Issue("session.${session.id}.inactivePaths", "对话路径标识重复"))
        session.inactivePaths.forEach { path ->
            val key = "session.${session.id}.inactivePaths.${path.id}"
            if (path.id.isBlank() || path.messages.map { it.id }.distinct().size != path.messages.size ||
                path.turnRecords.map { it.userMessageId }.distinct().size != path.turnRecords.size)
                add(Issue(key, "对话路径含空标识或重复消息/轮次"))
            if (path.turnRecords.any { record -> user(path, record.userMessageId) == null ||
                    (record.selectedVariantId != null && record.variants.none { it.id == record.selectedVariantId }) })
                add(Issue(key, "对话路径的轮次或候选来源不存在"))
            path.runtime.completedWorld?.let { world ->
                if (world.choices != path.choices) add(Issue(key, "对话路径资源选择与冻结环境不一致"))
            }
            val worlds = listOfNotNull(path.runtime.completedWorld) + path.turnRecords.flatMap { record ->
                listOfNotNull(record.checkpoint.runtime.completedWorld) + record.variants.mapNotNull { it.after.completedWorld }
            }
            worlds.forEach { world ->
                try { CompletedWorlds.requireValidWorld(world) }
                catch (failure: IllegalArgumentException) { add(Issue(key, failure.message ?: "对话路径冻结环境无效")) }
                catch (failure: IllegalStateException) { add(Issue(key, failure.message ?: "对话路径冻结环境无效")) }
            }
        }
    }

    fun userVersions(session: Session, messageId: String): List<TextRevision> {
        val current = snapshot(session)
        return (session.inactivePaths + current).filter { sameAncestors(current, it, messageId) }
            .mapNotNull { path -> user(path, messageId)?.takeUnless { it.deleted } }
            .map { TextRevision(revision(it), it.text, it.activeTextRevisionCreatedAt ?: it.createdAt, it.bodyDisplay) }
            .distinctBy { it.id }.sortedWith(compareBy<TextRevision> { it.createdAt }.thenBy { it.id })
    }

    /** Returned variants contain replies, including the selected variant's edited body. */
    fun generationVariants(session: Session, turnId: String): List<TurnVariant> =
        variants(snapshot(session), session.inactivePaths, turnId)

    fun selectUserVersion(session: Session, messageId: String, revisionId: String): MessageOperationResult {
        if (session.pendingRun != null) return unavailable("请先结束当前生成或恢复")
        val current = snapshot(session)
        val active = user(current, messageId) ?: return unavailable("用户消息不存在")
        if (revision(active) == revisionId) return MessageOperationResult(session)
        val target = session.inactivePaths.filter {
            sameAncestors(current, it, messageId) && user(it, messageId)?.let(::revision) == revisionId
        }.maxByOrNull { it.updatedAt } ?: return unavailable("此提示词版本没有可恢复的对话路径")
        return activate(session, target)
    }

    /** Atomic edit/send boundary. No old answer or old turn record belongs to the new revision. */
    fun editUserAndSend(session: Session, messageId: String, text: String): MessageOperationResult {
        if (session.pendingRun != null) return unavailable("请先结束当前生成或恢复")
        if (text.isBlank()) return unavailable("消息不能为空")
        val index = session.messages.indexOfFirst { it.id == messageId && it.role == Role.USER && !it.deleted }
        if (index < 0) return unavailable("用户消息不存在或已删除")
        val old = session.messages[index]
        val record = session.turnRecords.firstOrNull { it.userMessageId == messageId }
        val base = if (record != null) {
            // This existing operation checks restore eligibility and macro prefix integrity.
            val boundary = MessageOperations.forkAt(session, messageId)
            boundary.session ?: return boundary
        } else {
            if (session.messages.drop(index + 1).any { !it.deleted } ||
                session.messages.drop(index + 1).any { it.role == Role.USER })
                return unavailable("这一轮缺少可恢复的运行状态检查点")
            session
        }
        val now = System.currentTimeMillis()
        val replacement = old.copy(text = text, rawText = null, textRevisions = emptyList(),
            activeTextRevisionId = newId(), activeTextRevisionCreatedAt = now,
            bodyDisplay = null, reasoningSnapshot = null)
        return MessageOperationResult(base.copy(id = session.id, isPinned = session.isPinned,
            messages = session.messages.take(index) + replacement,
            turnRecords = base.turnRecords.takeWhile { it.userMessageId != messageId },
            inactivePaths = archive(session), pendingRun = null, updatedAt = now))
    }

    fun selectGenerationVariant(session: Session, turnId: String, variantId: String): MessageOperationResult {
        if (session.pendingRun != null) return unavailable("请先结束当前生成或恢复")
        val current = snapshot(session)
        val record = session.turnRecords.firstOrNull { it.userMessageId == turnId }
            ?: return unavailable("这一轮没有运行状态检查点")
        if (record.selectedVariantId == variantId) return MessageOperationResult(session)
        val target = session.inactivePaths.filter {
            samePrompt(current, it, turnId) && it.turnRecords.any { r -> r.userMessageId == turnId && r.selectedVariantId == variantId }
        }.maxByOrNull { it.updatedAt }
        if (target != null) return activate(session, target)
        val hydrated = hydrate(session)
        val restored = MessageOperations.forkAtVariant(hydrated, turnId, variantId)
        return restored.session?.let { selected -> MessageOperationResult(selected.copy(
            id = session.id, isPinned = session.isPinned, inactivePaths = archive(session), pendingRun = null
        )) } ?: restored
    }

    /** Historical regeneration stays in this chat and preserves its old continuation. */
    fun regenerationBase(session: Session, messageId: String): MessageOperationResult {
        if (session.pendingRun != null) return unavailable("请先结束当前生成或恢复")
        val target = session.messages.firstOrNull { it.id == messageId && !it.deleted }
            ?: return unavailable("消息不存在或已删除")
        val userId = (if (target.role == Role.USER) target.id else target.turnId)
            ?: return unavailable("这条回复没有用户轮次来源")
        val hydrated = hydrate(session)
        if (session.messages.lastOrNull { it.role == Role.USER && it.outputSlot == OutputSlot.BODY }?.id == userId) {
            val prepared = MessageOperations.prepareRegeneration(hydrated, messageId)
            return if (prepared.succeeded) MessageOperationResult(hydrated) else prepared
        }
        val selected = hydrated.turnRecords.firstOrNull { it.userMessageId == userId }?.selectedVariantId
            ?: return unavailable("这一轮没有可恢复的生成候选")
        val base = MessageOperations.forkAtVariant(hydrated, userId, selected)
        return base.session?.let { MessageOperationResult(it.copy(id = session.id, isPinned = session.isPinned,
            inactivePaths = archive(session), pendingRun = null)) } ?: base
    }

    private fun activate(session: Session, target: ConversationPathSnapshot): MessageOperationResult {
        val issues = validate(session.copy(inactivePaths = listOf(target)))
        if (issues.any { it.error }) return MessageOperationResult(issues = issues)
        if (target.turnRecords.any { !it.restoreAllowed }) return unavailable("此路径的运行状态被手动修改，不能恢复")
        val choices = target.choices
        val restored = session.withRuntime(target.runtime).copy(messages = target.messages,
            turnRecords = target.turnRecords, macroHistory = target.macroHistory,
            executableWorld = target.runtime.completedWorld, characterId = choices.characterId,
            mainCallId = choices.mainCallId, personaId = choices.personaId,
            packageIds = choices.packageIds, groupIds = choices.groupIds,
            enabledExtensionIds = choices.enabledExtensionIds,
            inactivePaths = archive(session).filterNot { it.id == target.id },
            pendingRun = null, updatedAt = System.currentTimeMillis())
        return MessageOperationResult(hydrate(restored))
    }

    private fun hydrate(session: Session): Session {
        val active = snapshot(session)
        return session.copy(turnRecords = session.turnRecords.map { record ->
            record.copy(variants = variants(active, session.inactivePaths, record.userMessageId).map { variant ->
                if (variant.id == record.selectedVariantId) variant.copy(replies = emptyList(),
                    replyIds = record.variants.firstOrNull { it.id == variant.id }?.let { old ->
                        old.replyIds.ifEmpty { old.replies.map { it.id } }
                    }.orEmpty()) else variant
            })
        })
    }

    private fun variants(active: ConversationPathSnapshot, inactive: List<ConversationPathSnapshot>, turnId: String): List<TurnVariant> {
        val byId = linkedMapOf<String, TurnVariant>()
        (inactive.sortedBy { it.updatedAt } + active).filter { samePrompt(active, it, turnId) }.forEach { path ->
            val record = path.turnRecords.firstOrNull { it.userMessageId == turnId } ?: return@forEach
            record.variants.forEach { variant ->
                val replies = if (variant.id == record.selectedVariantId) {
                    val ids = variant.replyIds.ifEmpty { variant.replies.map { it.id } }.toSet()
                    path.messages.filter { it.id in ids }.ifEmpty { variant.replies }
                } else variant.replies
                // A selected path owns its edited replies; inactive copies must not overwrite it.
                if (variant.id !in byId || variant.id == record.selectedVariantId)
                    byId[variant.id] = variant.copy(replies = replies, replyIds = emptyList())
            }
        }
        return byId.values.toList()
    }

    private fun archive(session: Session): List<ConversationPathSnapshot> {
        val current = snapshot(session)
        val key = choices(current)
        // A temporary historical regeneration base is only a prefix, not a replacement future.
        if (session.inactivePaths.any { val other = choices(it); other.size > key.size && other.take(key.size) == key })
            return session.inactivePaths
        return session.inactivePaths.filterNot { choices(it) == key } + current
    }

    private fun snapshot(session: Session): ConversationPathSnapshot {
        val last = session.turnRecords.lastOrNull()
        val runtime = session.runtimeSnapshot().let { it.copy(completedWorld = it.completedWorld ?: last?.variants
            ?.firstOrNull { variant -> variant.id == last.selectedVariantId }?.after?.completedWorld) }
        return ConversationPathSnapshot(messages = session.messages, turnRecords = session.turnRecords,
            runtime = runtime, macroHistory = session.macroHistory,
            choices = runtime.completedWorld?.choices ?: CapturedSessionChoices(session.characterId, session.mainCallId,
                session.personaId, session.packageIds, session.groupIds, session.enabledExtensionIds), updatedAt = session.updatedAt)
    }

    private fun choices(path: ConversationPathSnapshot): List<Choice> = path.messages
        .filter { it.role == Role.USER && it.outputSlot == OutputSlot.BODY }
        .map { Choice(it.id, revision(it), path.turnRecords.firstOrNull { record -> record.userMessageId == it.id }?.selectedVariantId) }

    private fun sameAncestors(a: ConversationPathSnapshot, b: ConversationPathSnapshot, id: String): Boolean {
        val left = choices(a); val right = choices(b)
        val ai = left.indexOfFirst { it.userId == id }; val bi = right.indexOfFirst { it.userId == id }
        return ai >= 0 && ai == bi && left.take(ai) == right.take(bi)
    }

    private fun samePrompt(a: ConversationPathSnapshot, b: ConversationPathSnapshot, id: String): Boolean =
        sameAncestors(a, b, id) && user(a, id)?.let(::revision) == user(b, id)?.let(::revision)

    private fun user(path: ConversationPathSnapshot, id: String) = path.messages.firstOrNull {
        it.id == id && it.role == Role.USER && it.outputSlot == OutputSlot.BODY
    }
    private fun revision(message: ChatMessage) = message.activeTextRevisionId ?: message.id
    private fun unavailable(message: String) = MessageOperationResult(issues = listOf(Issue("conversationPath", message)))
}
