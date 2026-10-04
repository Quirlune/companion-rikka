package dev.gray.companion.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Resource wire versions are independent of the Android SQLite table version. */
object LibraryFormat {
    const val CURRENT_VERSION = 10
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

    /** Additive resource upgrades preserve legacy addresses and each journal's execution version. */
    fun upgrade(library: Library): Library {
        checkVersion(library.schemaVersion)
        val named = MacroNaming.normalize(library)
        fun upgradeSession(session: Session): Session {
            val pending = session.pendingRun ?: return session
            val frozen = pending.frozenResources
            checkVersion(frozen.schemaVersion)
            val namedFrozen = MacroNaming.normalize(frozen)
            val upgradedFrozen = if (namedFrozen.schemaVersion == CURRENT_VERSION) namedFrozen
                else namedFrozen.copy(schemaVersion = CURRENT_VERSION)
            if (upgradedFrozen === frozen) return session
            return session.copy(pendingRun = pending.copy(
                frozenResources = upgradedFrozen
            ))
        }
        var changed = named.schemaVersion != CURRENT_VERSION
        val sessions = named.sessions.map { session ->
            upgradeSession(session).also { if (it !== session) changed = true }
        }
        val savedChats = named.savedChats.map { saved ->
            val snapshot = upgradeSession(saved.snapshot)
            if (snapshot === saved.snapshot) saved else saved.copy(snapshot = snapshot).also { changed = true }
        }
        return if (changed) named.copy(schemaVersion = CURRENT_VERSION, sessions = sessions, savedChats = savedChats)
            else named
    }

    private fun checkVersion(version: Int) {
        require(version in LEGACY_VERSION..CURRENT_VERSION) { "不支持的资源版本：$version" }
    }
}
