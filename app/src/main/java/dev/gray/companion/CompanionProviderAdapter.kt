package dev.gray.companion

import dev.gray.companion.core.ApiConnection
import dev.gray.companion.core.Issue
import dev.gray.companion.core.Library
import dev.gray.companion.core.ModelProfile
import dev.gray.companion.core.Protocol
import dev.gray.companion.core.RequestHeader
import java.util.UUID
import kotlin.uuid.Uuid
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import me.rerere.rikkahub.data.datastore.DEFAULT_PROVIDERS

/** Fields which have no counterpart on the original provider presentation model. */
internal data class CompanionConnectionExtras(
    val customHeaders: List<RequestHeader> = emptyList(),
    val maxConcurrentRequests: Int = 2,
)

/** Ephemeral projection only. Credentials are deliberately absent, including from [providers]. */
internal data class CompanionProviderSnapshot(
    val connections: List<ApiConnection>,
    val profiles: List<ModelProfile>,
    val providers: List<ProviderSetting>,
    val connectionIds: Map<Uuid, String>,
    val profileIds: Map<Uuid, String>,
    val extras: Map<Uuid, CompanionConnectionExtras>,
) {
    fun providerId(connectionId: String): Uuid? = connectionIds.entries.firstOrNull { it.value == connectionId }?.key
}

/** Fixed resource images; Controller alone validates/persists these and the explicit encrypted-key diff. */
internal data class CompanionProviderPlan(
    val expectedConnections: List<ApiConnection>,
    val expectedProfiles: List<ModelProfile>,
    val connections: List<ApiConnection>,
    val profiles: List<ModelProfile>,
    val credentialChanges: List<CredentialWrite> = emptyList(),
)

internal data class CompanionProviderPlanResult(val plan: CompanionProviderPlan?, val issues: List<Issue> = emptyList())
internal data class CompanionProviderApply(val library: Library?, val issues: List<Issue> = emptyList())

/** No SettingsStore, provider requests, credential reads/writes or generated runtime configuration. */
internal object CompanionProviderAdapter {
    /** Original built-ins are real connections in the same resource authority, never a second settings store. */
    fun withBuiltInProviders(library: Library): Library {
        val connections = library.connections.toMutableList()
        DEFAULT_PROVIDERS.forEach { preset ->
            val id = "provider:${preset.id}"
            // Distinct connections may share an endpoint but use different credentials.
            if (connections.none { it.id == id }) {
                connections += ApiConnection(id = id, name = preset.name, protocol = preset.protocol(),
                    baseUrl = preset.baseUrl(), enabled = preset.enabled,
                    responseApi = (preset as? ProviderSetting.OpenAI)?.useResponseApi ?: false)
            }
        }
        return if (connections == library.connections) library else library.copy(connections = connections)
    }

    fun capture(library: Library): CompanionProviderSnapshot {
        require(library.connections.map { it.id }.distinct().size == library.connections.size) { "API 连接标识重复" }
        require(library.profiles.map { it.id }.distinct().size == library.profiles.size) { "模型标识重复" }
        require(library.profiles.all { profile -> library.connections.any { it.id == profile.apiConnectionId } }) {
            "模型必须关联实际 API 连接；旧配置应先通过现有 ModelCatalog 迁移"
        }
        val connectionIds = library.connections.associate { nativeConnectionId(it) to it.id }
        val profileIds = library.profiles.associate { opaqueId("profile", it.id) to it.id }
        require(connectionIds.size == library.connections.size && profileIds.size == library.profiles.size) { "原生界面标识冲突" }
        val providers = library.connections.map { connection ->
            val id = nativeConnectionId(connection)
            val models = library.profiles.filter { it.apiConnectionId == connection.id }.map { profile ->
                Model(id = opaqueId("profile", profile.id), modelId = profile.model, displayName = profile.name)
            }
            val projected = when (connection.protocol) {
                Protocol.OPENAI -> ProviderSetting.OpenAI(id = id, enabled = connection.enabled, name = connection.name,
                    models = models, baseUrl = connection.baseUrl, useResponseApi = connection.responseApi)
                Protocol.GOOGLE -> ProviderSetting.Google(id = id, enabled = connection.enabled, name = connection.name,
                    models = models, baseUrl = connection.baseUrl)
                Protocol.CLAUDE -> ProviderSetting.Claude(id = id, enabled = connection.enabled, name = connection.name,
                    models = models, baseUrl = connection.baseUrl)
            }
            // Keep the original descriptions and built-in affordances without importing credentials or models.
            val preset = DEFAULT_PROVIDERS.firstOrNull { connection.id == "provider:${it.id}" }
            if (preset == null) projected else projected.copyProvider(builtIn = true,
                description = preset.description, shortDescription = preset.shortDescription)
        }
        return CompanionProviderSnapshot(library.connections, library.profiles, providers, connectionIds, profileIds,
            library.connections.associate { nativeConnectionId(it) to CompanionConnectionExtras(it.customHeaders, it.maxConcurrentRequests) })
    }

    fun plan(
        snapshot: CompanionProviderSnapshot,
        desiredProviders: List<ProviderSetting>,
        connectionExtras: Map<Uuid, CompanionConnectionExtras> = snapshot.extras,
        editedApiKeys: Map<Uuid, Pair<String, String>> = emptyMap(),
    ): CompanionProviderPlanResult = try {
        require(desiredProviders.map { it.id }.distinct().size == desiredProviders.size) { "提供商标识重复" }
        val allModels = desiredProviders.flatMap { it.models }
        require(allModels.map { it.id }.distinct().size == allModels.size) { "模型标识重复或被放到多个提供商" }
        require(editedApiKeys.keys.all { id -> desiredProviders.any { it.id == id } }) { "密钥修改的提供商已不存在" }
        val oldConnections = snapshot.connections.associateBy { it.id }
        val oldProfiles = snapshot.profiles.associateBy { it.id }
        val connectionIds = desiredProviders.associate { provider ->
            provider.id to (snapshot.connectionIds[provider.id] ?: "provider:${provider.id}")
        }
        val connections = desiredProviders.map { provider ->
            val id = connectionIds.getValue(provider.id)
            require(snapshot.connectionIds.containsKey(provider.id) || id !in oldConnections) { "新提供商标识与已有资源冲突" }
            val old = oldConnections[id] ?: ApiConnection(id = id)
            val extras = connectionExtras[provider.id] ?: snapshot.extras[provider.id] ?: CompanionConnectionExtras()
            old.copy(name = provider.name, protocol = provider.protocol(), baseUrl = provider.baseUrl(),
                enabled = provider.enabled,
                responseApi = if (provider is ProviderSetting.OpenAI) provider.useResponseApi else old.responseApi,
                customHeaders = extras.customHeaders, maxConcurrentRequests = extras.maxConcurrentRequests)
        }
        val modelsByConnection = desiredProviders.associate { provider ->
            val connectionId = connectionIds.getValue(provider.id)
            connectionId to provider.models.map { model ->
                val id = snapshot.profileIds[model.id] ?: "profile:${model.id}"
                require(snapshot.profileIds.containsKey(model.id) || id !in oldProfiles) { "新模型标识与已有资源冲突" }
                val previous = oldProfiles[id]
                require(previous == null || previous.apiConnectionId == connectionId) { "不能通过提供商表单隐式移动已有模型的连接" }
                val profile = previous ?: ModelProfile(id = id, protocol = provider.protocol(), baseUrl = provider.baseUrl(),
                    responseApi = (provider as? ProviderSetting.OpenAI)?.useResponseApi ?: false, apiConnectionId = connectionId)
                // Keep every advanced request value and legacy fallback transport field of existing profiles.
                profile.copy(name = model.displayName, model = model.modelId)
            }.toMutableList()
        }
        // Reordering providers must not regroup/interleave the persisted global profile list.
        val profiles = buildList {
            snapshot.profiles.forEach { old -> old.apiConnectionId?.let { modelsByConnection[it] }?.let { pending ->
                if (pending.isNotEmpty()) add(pending.removeAt(0))
            } }
            desiredProviders.forEach { provider -> addAll(modelsByConnection.getValue(connectionIds.getValue(provider.id))) }
        }
        val keys = editedApiKeys.mapNotNull { (providerId, edit) ->
            if (edit.first == edit.second) null else CredentialWrite(connectionIds.getValue(providerId), edit.first, edit.second)
        }
        CompanionProviderPlanResult(CompanionProviderPlan(snapshot.connections, snapshot.profiles, connections, profiles, keys))
    } catch (failure: IllegalArgumentException) {
        CompanionProviderPlanResult(null, listOf(Issue("providers", failure.message ?: "提供商修改无效")))
    }

    /** Whole-library admission and reference validation must still run in AppController before saving. */
    fun apply(plan: CompanionProviderPlan, current: Library): CompanionProviderApply {
        if (current.connections != plan.expectedConnections || current.profiles != plan.expectedProfiles)
            return CompanionProviderApply(null, listOf(Issue("providers", "提供商或模型已被其他操作修改，请重新打开后再保存")))
        return CompanionProviderApply(current.copy(connections = plan.connections, profiles = plan.profiles,
            selectedProfileId = if (current.selectedProfileId == null && plan.expectedProfiles.isEmpty()) plan.profiles.firstOrNull()?.id
                else current.selectedProfileId?.takeIf { id -> plan.profiles.any { it.id == id } }))
    }

    fun withApiKey(provider: ProviderSetting, key: String): ProviderSetting = when (provider) {
        is ProviderSetting.OpenAI -> provider.copy(apiKey = key)
        is ProviderSetting.Google -> provider.copy(apiKey = key)
        is ProviderSetting.Claude -> provider.copy(apiKey = key)
    }

    fun apiKey(provider: ProviderSetting): String = when (provider) {
        is ProviderSetting.OpenAI -> provider.apiKey
        is ProviderSetting.Google -> provider.apiKey
        is ProviderSetting.Claude -> provider.apiKey
    }

    private fun opaqueId(kind: String, id: String): Uuid =
        Uuid.parse(UUID.nameUUIDFromBytes("companion-native-provider:$kind:$id".toByteArray(Charsets.UTF_8)).toString())

    private fun nativeConnectionId(connection: ApiConnection): Uuid =
        DEFAULT_PROVIDERS.firstOrNull { connection.id == "provider:${it.id}" }?.id
            ?: opaqueId("connection", connection.id)

    private fun ProviderSetting.protocol(): Protocol = when (this) {
        is ProviderSetting.OpenAI -> Protocol.OPENAI
        is ProviderSetting.Google -> Protocol.GOOGLE
        is ProviderSetting.Claude -> Protocol.CLAUDE
    }

    private fun ProviderSetting.baseUrl(): String = when (this) {
        is ProviderSetting.OpenAI -> baseUrl
        is ProviderSetting.Google -> baseUrl
        is ProviderSetting.Claude -> baseUrl
    }
}
