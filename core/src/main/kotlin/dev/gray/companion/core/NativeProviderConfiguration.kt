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

    fun validateProvider(payload: JsonObject?, path: String): List<Issue> =
        validatePayload(payload, path, provider = true)

    fun validateModel(payload: JsonObject?, path: String): List<Issue> =
        validatePayload(payload, path, provider = false)

    /** Inspect typed configuration locations, including archived preparation, without replaying journals. */
    fun validate(library: Library): List<Issue> {
        val issues = mutableListOf<Issue>()
        val pending = ArrayDeque<Pair<String, Any>>()
        val seen = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
        pending.addLast("library" to library)
        while (pending.isNotEmpty()) {
            val (path, value) = pending.removeFirst()
            if (!seen.add(value)) continue
            when (value) {
                is Library -> {
                    value.connections.forEach { pending.addLast("$path.connections.${it.id}" to it) }
                    value.profiles.forEach { pending.addLast("$path.profiles.${it.id}" to it) }
                    value.sessions.forEach { pending.addLast("$path.sessions.${it.id}" to it) }
                    value.savedChats.forEach { pending.addLast("$path.savedChats.${it.id}.snapshot" to it.snapshot) }
                }
                is ApiConnection -> issues += validateProvider(value.nativeProvider, "$path.nativeProvider")
                is ModelProfile -> issues += validateModel(value.nativeModel, "$path.nativeModel")
                is Session -> value.pendingRun?.let { pending.addLast("$path.pendingRun" to it) }
                is RunRecovery -> {
                    pending.addLast("$path.frozenResources" to value.frozenResources)
                    pending.addLast("$path.initialSession" to value.initialSession)
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

    private data class Node(val value: JsonObject, val path: String, val provider: Boolean)

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
                    is JsonObject -> pending.addLast(Node(overwrite, "${node.path}.providerOverwrite", true))
                    else -> issues += Issue("${node.path}.providerOverwrite", "模型覆盖提供商配置须为对象")
                }
            }
            // customBodies and all other opaque options are provider data, not credential locations.
        }
        return issues
    }
}
