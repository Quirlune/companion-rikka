package dev.gray.companion.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Resource wire versions are independent of the Android SQLite table version. */
object LibraryFormat {
    const val CURRENT_VERSION = 2
    private const val LEGACY_VERSION = 1

    val json = Json { ignoreUnknownKeys = false; encodeDefaults = true }

    fun encode(library: Library): String =
        json.encodeToString(Library.serializer(), upgrade(library))

    fun decode(text: String): Library {
        val document = json.parseToJsonElement(text) as? JsonObject
            ?: error("资源文件须为对象")
        val versionField = document["schemaVersion"]
        val version = if (versionField == null) LEGACY_VERSION else
            (versionField as? JsonPrimitive)?.intOrNull ?: error("资源版本须为整数")
        checkVersion(version)
        val decoded = json.decodeFromJsonElement(Library.serializer(), document)
        return upgrade(if (decoded.schemaVersion == version) decoded else decoded.copy(schemaVersion = version))
    }

    /** Version 2 names the journal contract; version 1 keeps the same execution meaning. */
    fun upgrade(library: Library): Library {
        checkVersion(library.schemaVersion)
        fun upgradeSession(session: Session): Session {
            val pending = session.pendingRun ?: return session
            val frozen = pending.frozenResources
            checkVersion(frozen.schemaVersion)
            if (frozen.schemaVersion == CURRENT_VERSION) return session
            return session.copy(pendingRun = pending.copy(
                frozenResources = frozen.copy(schemaVersion = CURRENT_VERSION)
            ))
        }
        var changed = library.schemaVersion != CURRENT_VERSION
        val sessions = library.sessions.map { session ->
            upgradeSession(session).also { if (it !== session) changed = true }
        }
        val savedChats = library.savedChats.map { saved ->
            val snapshot = upgradeSession(saved.snapshot)
            if (snapshot === saved.snapshot) saved else saved.copy(snapshot = snapshot).also { changed = true }
        }
        return if (changed) library.copy(schemaVersion = CURRENT_VERSION, sessions = sessions, savedChats = savedChats)
            else library
    }

    private fun checkVersion(version: Int) {
        require(version == LEGACY_VERSION || version == CURRENT_VERSION) { "不支持的资源版本：$version" }
    }
}
