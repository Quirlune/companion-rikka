package dev.gray.companion.core

import kotlinx.serialization.Serializable

@Serializable data class ChatFolder(val id: String = newId(), val name: String = "新文件夹")
@Serializable data class SavedChat(val id: String = newId(), val folderId: String, val snapshot: Session, val savedAt: Long = System.currentTimeMillis())
@Serializable data class UserIdentity(val name: String = "用户", val avatar: AvatarImage? = null)

object ChatArchive {
    fun save(library: Library, sessionId: String, folderId: String): Library {
        require(library.chatFolders.any { it.id == folderId }) { "收藏文件夹不存在" }
        val session = requireNotNull(library.sessions.firstOrNull { it.id == sessionId }) { "对话不存在" }
        // Core DTOs are immutable; subsequent edits replace the source Session, never this value.
        return library.copy(savedChats = library.savedChats + SavedChat(folderId = folderId, snapshot = session))
    }

    fun restore(library: Library, savedChatId: String): Library {
        val saved = requireNotNull(library.savedChats.firstOrNull { it.id == savedChatId }) { "收藏对话不存在" }
        val chat = saved.snapshot.copy(id = newId(), updatedAt = System.currentTimeMillis())
        return library.copy(sessions = listOf(chat) + library.sessions, selectedSessionId = chat.id)
    }
}
