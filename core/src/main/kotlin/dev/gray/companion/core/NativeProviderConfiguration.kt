package dev.gray.companion.core

import java.util.Collections
import java.util.IdentityHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Secret-free original provider payloads. The Android codec owns their transport interpretation. */
object NativeProviderConfiguration {
    const val FORMAT_VERSION = 1

    /** Same admission rule as an ordinary API connection, applied to effective native URLs too. */
    fun validHttpsAddress(value: String): Boolean = try {
        val uri = java.net.URI(value)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.query == null && uri.fragment == null
    } catch (_: Exception) { false }

    fun validateProvider(payload: JsonObject?, path: String): List<Issue> =
        validatePayload(payload, path, provider = true)

    fun validateModel(payload: JsonObject?, path: String): List<Issue> =
        validatePayload(payload, path, provider = false)

    /** Inspect typed configuration locations, including archived preparation, without replaying journals. */
    fun validate(library: Library): List<Issue> {
        val issues = mutableListOf<Issue>()
        val pending = ArrayDeque<Pair<String, Any>>()
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        fun validateResources(resources: Library, path: String, executionVersion: Int? = null) {
            issues += ExecutionResources.validate(resources, executionVersion).map { it.copy(path = "$path.${it.path}") }
        }
        pending.addLast("library" to library)
        while (pending.isNotEmpty()) {
            val (path, value) = pending.removeFirst()
            if (!seen.add(value)) continue
            when (value) {
                is Library -> {
                    validateResources(value, path)
                    value.memoryExecutionView?.let { pending.addLast("$path.memoryExecutionView.library" to it.library) }
                    value.connections.forEach { pending.addLast("$path.connections.${it.id}" to it) }
                    value.profiles.forEach { pending.addLast("$path.profiles.${it.id}" to it) }
                    value.sessions.forEach { pending.addLast("$path.sessions.${it.id}" to it) }
                    value.savedChats.forEach { pending.addLast("$path.savedChats.${it.id}.snapshot" to it.snapshot) }
                }
                is ApiConnection -> issues += validateProvider(value.nativeProvider, "$path.nativeProvider")
                is ModelProfile -> issues += validateModel(value.nativeModel, "$path.nativeModel")
                is Session -> {
                    value.pendingRun?.let { pending.addLast("$path.pendingRun" to it) }
                    value.executableWorld?.let { pending.addLast("$path.executableWorld" to it) }
                    value.turnRecords.forEachIndexed { index, record ->
                        pending.addLast("$path.turnRecords.$index.checkpoint.runtime" to record.checkpoint.runtime)
                        record.variants.forEachIndexed { variantIndex, variant ->
                            pending.addLast("$path.turnRecords.$index.variants.$variantIndex.after" to variant.after)
                        }
                    }
                }
                is RuntimeSnapshot -> value.completedWorld?.let { pending.addLast("$path.completedWorld" to it) }
                is CompletedWorld -> {
                    // Version admission belongs to the containing execution, even if this Library
                    // instance has already been visited through another snapshot.
                    validateResources(value.library, "$path.library", value.executionVersion)
                    if (value.library.sessions.isNotEmpty() || value.library.savedChats.isNotEmpty() ||
                        value.library.chatFolders.isNotEmpty())
                        issues += Issue("$path.library", "分支资源环境不能包含会话或收藏")
                    pending.addLast("$path.library" to value.library)
                    value.environments.forEachIndexed { index, environment ->
                        val session = environment.session
                        if (session.turnRecords.isNotEmpty() || session.pendingRun != null || session.executableWorld != null)
                            issues += Issue("$path.environments.$index.session", "重算环境不能递归包含分支或恢复记录")
                        pending.addLast("$path.environments.$index.session" to session)
                    }
                }
                is RunRecovery -> {
                    validateResources(value.frozenResources, "$path.frozenResources", value.executionVersion)
                    pending.addLast("$path.frozenResources" to value.frozenResources)
                    pending.addLast("$path.checkpoint.runtime" to value.checkpoint.runtime)
                    pending.addLast("$path.initialSession" to value.initialSession)
                    value.beforeUserSession?.let { pending.addLast("$path.beforeUserSession" to it) }
                    value.regenerationBase?.let { pending.addLast("$path.regenerationBase" to it) }
                    value.environments.forEachIndexed { index, environment ->
                        pending.addLast("$path.environments.$index.session" to environment.session)
                    }
                    value.prepared.forEachIndexed { index, prepared ->
                        prepared.profile?.let { pending.addLast("$path.prepared.$index.profile" to it) }
                    }
                }
            }
        }
        return issues
    }

    private data class Node(val value: JsonObject, val path: String, val provider: Boolean,
        val effectiveOverride: Boolean = false)

    private fun validatePayload(payload: JsonObject?, path: String, provider: Boolean): List<Issue> {
        if (payload == null) return emptyList()
        val issues = mutableListOf<Issue>()
        val pending = ArrayDeque<Node>()
        pending.addLast(Node(payload, path, provider))
        while (pending.isNotEmpty()) {
            val node = pending.removeFirst()
            if (node.value === payload || "_formatVersion" in node.value) {
                val version = node.value["_formatVersion"] as? JsonPrimitive
                if (version == null || version.isString || version.intOrNull != FORMAT_VERSION)
                    issues += Issue("${node.path}._formatVersion", "不支持的原版提供商配置版本")
            }
            if (node.provider) {
                if (node.effectiveOverride) {
                    val address = node.value["baseUrl"] as? JsonPrimitive
                    if (address != null && (!address.isString || !validHttpsAddress(address.content)))
                        issues += Issue("${node.path}.baseUrl", "模型覆盖地址必须是有效的 HTTPS 地址")
                }
                for (key in listOf("apiKey", "privateKey")) {
                    val secret = node.value[key]
                    if (secret != null && secret != JsonNull &&
                        !(secret is JsonPrimitive && secret.isString && secret.content.isEmpty()))
                        issues += Issue("${node.path}.$key", "提供商密钥只能保存在加密凭据库中")
                }
                when (val models = node.value["models"]) {
                    null, JsonNull -> Unit
                    is JsonArray -> models.forEachIndexed { index, model ->
                        if (model is JsonObject) pending.addLast(Node(model, "${node.path}.models.$index", false))
                        else issues += Issue("${node.path}.models.$index", "原版模型配置须为对象")
                    }
                    else -> issues += Issue("${node.path}.models", "原版模型列表须为数组")
                }
            } else {
                when (val overwrite = node.value["providerOverwrite"]) {
                    null, JsonNull -> Unit
                    is JsonObject -> pending.addLast(Node(overwrite, "${node.path}.providerOverwrite", true, true))
                    else -> issues += Issue("${node.path}.providerOverwrite", "模型覆盖提供商配置须为对象")
                }
            }
            // customBodies and all other opaque options are provider data, not credential locations.
        }
        return issues
    }
}
