package dev.gray.companion

import dev.gray.companion.core.CredentialStore
import dev.gray.companion.core.Issue
import dev.gray.companion.core.Library
import dev.gray.companion.core.LibraryRepository
import dev.gray.companion.core.CredentialKind
import dev.gray.companion.core.CredentialSlot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Ephemeral edits only; credentials never enter the resource library or its export. */
internal class CredentialWrite(val connectionId: String, val before: String, val after: String,
                              val kind: CredentialKind = CredentialKind.API_KEY) {
    val slot: CredentialSlot get() = CredentialSlot(connectionId, kind)
}

/** Called after validation under the controller's existing admission mutex. */
internal object ConfigurationCredentialCommit {
    suspend fun save(repository: LibraryRepository, credentials: CredentialStore,
                     next: Library, writes: List<CredentialWrite>): List<Issue> = withContext(NonCancellable) {
        if (writes.map { it.slot }.distinct().size != writes.size ||
            writes.any { write -> when (write.kind) {
                CredentialKind.API_KEY, CredentialKind.PRIVATE_KEY -> next.connections.none { it.id == write.connectionId }
                CredentialKind.MODEL_API_KEY, CredentialKind.MODEL_PRIVATE_KEY -> next.profiles.none { it.id == write.connectionId }
                CredentialKind.PLUGIN_SECRET -> true // Never accept plugin secrets through provider configuration writes.
            } })
            return@withContext listOf(Issue("connection.apiKey", "密钥编辑目标重复或已不存在"))
        for (write in writes) {
            val current = try { credentials.get(write.slot) }
            catch (_: Exception) { return@withContext listOf(Issue("connection.apiKey", "无法读取原密钥，配置未保存")) }
            if (current != write.before)
                return@withContext listOf(Issue("connection.${write.connectionId}.apiKey", "密钥已被修改，请重新打开连接配置"))
        }
        val attempted = mutableListOf<CredentialWrite>()
        try {
            for (write in writes) {
                attempted += write // A failed store call may already have changed its value.
                credentials.put(write.slot, write.after)
            }
            repository.save(next)
            emptyList()
        } catch (failure: Exception) {
            var restored = true
            for (write in attempted.asReversed()) {
                try {
                    if (write.before.isEmpty()) credentials.remove(write.slot)
                    else credentials.put(write.slot, write.before)
                } catch (_: Exception) { restored = false }
            }
            if (failure is CancellationException) throw failure
            buildList {
                add(Issue("library", "保存失败；资源配置未应用"))
                if (!restored) add(Issue("connection.apiKey", "原密钥恢复失败，请重新检查连接密钥"))
            }
        }
    }
}
