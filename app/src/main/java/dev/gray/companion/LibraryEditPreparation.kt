package dev.gray.companion

import dev.gray.companion.core.*

/** Pure edit rules used before the controller validates and commits one library revision. */
internal object LibraryEditPreparation {
    /** Collection definitions use their own editor; ordinary associations no longer have collection owners. */
    fun extensionMetadataIssues(before: Library, edited: Library): List<Issue> = buildList {
        if (before.extensions != edited.extensions)
            add(Issue("extensions", "请通过集合编辑或配置导入修改集合定义"))
    }

    private fun changesRecoveryContext(previous: Session, next: Session): Boolean =
        next.messages != previous.messages || next.turnRecords != previous.turnRecords ||
            next.characterId != previous.characterId || next.mainCallId != previous.mainCallId ||
            next.packageIds != previous.packageIds || next.groupIds != previous.groupIds ||
            next.personaId != previous.personaId || next.enabledExtensionIds != previous.enabledExtensionIds ||
            next.runtimeSnapshot() != previous.runtimeSnapshot() || next.macroHistory != previous.macroHistory

    /** A recovery journal is optional continuation data, not an edit lock. */
    fun prepareRecoveryEdits(before: Library, edited: Library): Library {
        val oldSessions = before.sessions.associateBy { it.id }
        val memoryPolicyChanged = before.memoryConfig?.enabled != edited.memoryConfig?.enabled ||
            before.memoryConfig?.extensionId != edited.memoryConfig?.extensionId
        return edited.copy(sessions = edited.sessions.map { next ->
            val previous = oldSessions[next.id]
            if (previous?.pendingRun != null && next.pendingRun != null &&
                (memoryPolicyChanged || changesRecoveryContext(previous, next)))
                MessageOperations.discardRecovery(next)
            else next
        })
    }

    fun normalize(before: Library, edited: Library): Library =
        LibraryFormat.upgrade(prepareRecoveryEdits(before, edited).withStandardCharacterFields()).let { normalized ->
            val oldSessions = before.sessions.associateBy { it.id }
            normalized.copy(sessions = normalized.sessions.map { session ->
                val previous = oldSessions[session.id]
                val named = if (previous != null && before.userName(previous) != normalized.userName(session)) {
                    val legacyIds = previous.messages.asSequence()
                        .filter { it.role == Role.USER && it.speakerName == null }.map { it.id }.toSet()
                    if (legacyIds.isEmpty()) session else session.copy(messages = session.messages.map { message ->
                        if (message.id in legacyIds && message.speakerName == null)
                            message.copy(speakerName = before.userName(previous)) else message
                    })
                } else session
                if (previous != null && (previous.runtimeSnapshot() != session.runtimeSnapshot() ||
                        previous.macroHistory != session.macroHistory)) MessageOperations.invalidateRestore(named)
                else named
            })
        }

    fun removalIssues(before: Library, next: Library): List<Issue> {
        val removed = (before.characters.map { it.id } - next.characters.map { it.id }.toSet()) +
            (before.mainCalls.map { it.id } - next.mainCalls.map { it.id }.toSet()) +
            (before.packages.map { it.id } - next.packages.map { it.id }.toSet()) +
            (before.groups.map { it.id } - next.groups.map { it.id }.toSet())
        if (removed.isEmpty()) return emptyList()
        val refs = (next.sessions + next.savedChats.map { it.snapshot })
            .flatMap { listOf(it.characterId, it.mainCallId) + it.packageIds + it.groupIds } +
            next.characters.flatMap { it.boundPackageIds + it.boundGroupIds } +
            next.mainCalls.flatMap { it.boundPackageIds + it.boundGroupIds } +
            next.groups.flatMap { it.boundPackageIds }
        return removed.filter { it in refs }.map { Issue("resource.$it", "资源仍被会话或绑定使用") }
    }
}
