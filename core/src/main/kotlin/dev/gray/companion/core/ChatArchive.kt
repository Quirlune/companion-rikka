package dev.gray.companion.core

import kotlinx.serialization.Serializable

@Serializable data class ChatFolder(val id: String = newId(), val name: String = "新文件夹")
@Serializable data class SavedChat(val id: String = newId(), val folderId: String, val snapshot: Session, val savedAt: Long = System.currentTimeMillis())
@Serializable data class UserIdentity(val name: String = "用户", val avatar: AvatarImage? = null)

object ChatArchive {
    /** One-click collection; never move, link or replace the source conversation. */
    fun saveCurrentCopy(library: Library, sessionId: String): Library {
        val folder = library.chatFolders.firstOrNull { it.name == "收藏" } ?: ChatFolder(name = "收藏")
        val prepared = if (library.chatFolders.any { it.id == folder.id }) library
            else library.copy(chatFolders = library.chatFolders + folder)
        return save(prepared, sessionId, folder.id)
    }

    fun save(library: Library, sessionId: String, folderId: String): Library {
        require(library.chatFolders.any { it.id == folderId }) { "收藏文件夹不存在" }
        val session = requireNotNull(library.sessions.firstOrNull { it.id == sessionId }) { "对话不存在" }
        // Capture choices at save time: they may have changed since the last completed turn.
        val resources = session.pendingRun?.frozenResources ?: CompletedWorlds.executionLibrary(library, session)
        val version = session.pendingRun?.executionVersion ?: session.executableWorld?.executionVersion
            ?: ExecutionSemantics.CURRENT
        val world = CompletedWorlds.capture(resources, session, "", version)
        val snapshotId = newId()
        val snapshot = session.copy(id = snapshotId, executableWorld = world,
            pendingRun = session.pendingRun?.rebindForRestore(session.id, snapshotId))
        return library.copy(savedChats = library.savedChats + SavedChat(folderId = folderId, snapshot = snapshot),
            illustrationImages = library.illustrationImages + library.illustrationImages
                .filter { it.sessionId == session.id }.map { it.copy(sessionId = snapshotId) })
    }

    /** Copy all conversation state; only the owner identity and displayed name change. */
    fun duplicate(library: Library, sessionId: String): Library {
        val source = requireNotNull(library.sessions.firstOrNull { it.id == sessionId }) { "对话不存在" }
        val id = newId()
        val duplicate = source.copy(id = id, title = source.title + " 副本",
            pendingRun = source.pendingRun?.rebindForRestore(source.id, id))
        return library.copy(sessions = listOf(duplicate) + library.sessions, selectedSessionId = id)
    }

    fun restore(library: Library, savedChatId: String): Library {
        val saved = requireNotNull(library.savedChats.firstOrNull { it.id == savedChatId }) { "收藏对话不存在" }
        val restoredId = newId()
        val chat = saved.snapshot.copy(id = restoredId,
            folderId = saved.snapshot.folderId?.takeIf { id -> library.chatFolders.any { it.id == id } },
            pendingRun = saved.snapshot.pendingRun?.rebindForRestore(saved.snapshot.id, restoredId),
            updatedAt = System.currentTimeMillis())
        return library.copy(sessions = listOf(chat) + library.sessions, selectedSessionId = chat.id,
            illustrationImages = library.illustrationImages + library.illustrationImages
                .filter { it.sessionId == saved.snapshot.id }.map { it.copy(sessionId = restoredId) })
    }

    private fun RunRecovery.rebindForRestore(sourceId: String, restoredId: String): RunRecovery {
        // Historical regeneration owns a separate candidate conversation. Restoring the archive
        // must copy that identity too, without turning it into an in-place latest-turn regeneration.
        val candidateId = sessionId.takeIf { regenerating && it != sourceId &&
            initialSession.id == it && regenerationBase?.id == it }
        val restoredCandidateId = candidateId?.let { newId() }
        fun mapped(id: String): String = when (id) {
            sourceId -> restoredId
            candidateId -> restoredCandidateId ?: id
            else -> id
        }
        fun rebound(session: Session): Session = session.copy(id = mapped(session.id))
        return copy(sessionId = mapped(sessionId), initialSession = rebound(initialSession),
            environments = environments.map { it.copy(session = rebound(it.session)) },
            regenerationBase = regenerationBase?.let(::rebound),
            beforeUserSession = beforeUserSession?.let(::rebound))
    }
}
