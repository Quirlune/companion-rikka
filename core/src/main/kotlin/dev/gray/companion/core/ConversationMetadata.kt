package dev.gray.companion.core

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Local conversation labels and organization never invoke a model or change runtime state. */
object ConversationMetadata {
    fun defaultTitle(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").format(Instant.ofEpochMilli(now).atZone(zone))

    fun rename(library: Library, sessionId: String, title: String): Library {
        val name = title.trim()
        require(name.isNotEmpty()) { "聊天名称不能为空" }
        require(library.sessions.any { it.id == sessionId }) { "对话不存在" }
        return library.copy(sessions = library.sessions.map {
            if (it.id == sessionId) it.copy(title = name, titlePending = false) else it
        })
    }

    fun createFolder(library: Library, name: String): Library {
        val title = name.trim()
        require(title.isNotEmpty()) { "文件夹名称不能为空" }
        return library.copy(chatFolders = library.chatFolders + ChatFolder(name = title))
    }

    fun renameFolder(library: Library, folderId: String, name: String): Library {
        val title = name.trim()
        require(title.isNotEmpty()) { "文件夹名称不能为空" }
        require(library.chatFolders.any { it.id == folderId }) { "文件夹不存在" }
        return library.copy(chatFolders = library.chatFolders.map { if (it.id == folderId) it.copy(name = title) else it })
    }

    fun deleteFolder(library: Library, folderId: String): Library {
        require(library.chatFolders.any { it.id == folderId }) { "文件夹不存在" }
        require(library.savedChats.none { it.folderId == folderId }) { "文件夹仍有收藏，请先移走或删除收藏" }
        return library.copy(chatFolders = library.chatFolders.filterNot { it.id == folderId },
            sessions = library.sessions.map { if (it.folderId == folderId) it.copy(folderId = null) else it })
    }

    fun move(library: Library, sessionId: String, folderId: String?): Library {
        require(library.sessions.any { it.id == sessionId }) { "对话不存在" }
        require(folderId == null || library.chatFolders.any { it.id == folderId }) { "文件夹不存在" }
        return library.copy(sessions = library.sessions.map { if (it.id == sessionId) it.copy(folderId = folderId) else it })
    }
}
