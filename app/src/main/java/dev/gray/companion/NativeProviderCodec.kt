package dev.gray.companion

import dev.gray.companion.core.ApiConnection
import dev.gray.companion.core.CredentialKind
import dev.gray.companion.core.CredentialSlot
import dev.gray.companion.core.Issue
import dev.gray.companion.core.Library
import dev.gray.companion.core.ModelCatalog
import dev.gray.companion.core.ModelProfile
import dev.gray.companion.core.NativeProviderConfiguration
import dev.gray.companion.core.Protocol
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import kotlin.uuid.Uuid

internal data class NativeModelRequest(val provider: ProviderSetting, val model: Model)

/** The original serializers own transport options. Core owns resource indexes and custom bodies.
 * No persistence, SettingsStore, credential cache or network work belongs to this boundary.
 */
internal object NativeProviderCodec {
    private val json = Json { encodeDefaults = true }
    private val providerIndexes = setOf("id", "name", "enabled", "baseUrl", "useResponseApi", "models")
    private val modelIndexes = setOf("id", "modelId", "displayName", "customBodies")

    fun provider(
        connection: ApiConnection,
        id: Uuid,
        models: List<Model>,
        preset: ProviderSetting? = null,
    ): ProviderSetting = safe("原版提供商配置无效") {
        require(NativeProviderConfiguration.validateProvider(connection.nativeProvider, "provider").isEmpty())
        val base = when (connection.protocol) {
            Protocol.OPENAI -> ProviderSetting.OpenAI(id = id, name = connection.name, enabled = connection.enabled,
                baseUrl = connection.baseUrl, useResponseApi = connection.responseApi)
            Protocol.GOOGLE -> ProviderSetting.Google(id = id, name = connection.name, enabled = connection.enabled,
                baseUrl = connection.baseUrl)
            Protocol.CLAUDE -> ProviderSetting.Claude(id = id, name = connection.name, enabled = connection.enabled,
                baseUrl = connection.baseUrl)
        }
        val encodedBase = encodeProvider(base)
        val options = connection.nativeProvider?.let(::withoutEnvelope).orEmpty()
        val indexed = encodedBase.filterKeys { it in providerIndexes || it == "type" }
        val decoded = decodeProvider(JsonObject(encodedBase + options + indexed))
        val projected = sanitizeProvider(decoded.copyProvider(models = models))
        if (preset == null) projected else projected.copyProvider(builtIn = preset.builtIn,
            description = preset.description, shortDescription = preset.shortDescription)
    }

    fun model(profile: ModelProfile, id: Uuid): Model = safe("原版模型配置无效") {
        require(NativeProviderConfiguration.validateModel(profile.nativeModel, "model").isEmpty())
        val base = Model(id = id, modelId = profile.model, displayName = profile.name,
            customBodies = profile.customBody.map { CustomBody(it.key, it.value) })
        val encodedBase = encodeModel(base)
        val options = profile.nativeModel?.let(::withoutEnvelope).orEmpty()
        val indexed = encodedBase.filterKeys { it in modelIndexes }
        decodeModel(cleanModel(JsonObject(encodedBase + options + indexed), dropProviderModels = true))
    }

    fun providerOptions(provider: ProviderSetting): JsonObject = envelope(JsonObject(
        cleanProvider(encodeProvider(provider), dropModels = true).filterKeys { it !in providerIndexes }))

    fun modelOptions(model: Model): JsonObject = envelope(JsonObject(
        cleanModel(encodeModel(model), dropProviderModels = true).filterKeys { it !in modelIndexes }))

    /** For form drafts and sharing; leaves top-level models intact, with all nested secrets removed. */
    fun sanitizeProvider(provider: ProviderSetting): ProviderSetting =
        decodeProvider(cleanProvider(encodeProvider(provider), dropModels = false))

    /** Hydrate only a details draft. An absent mapping must never guess or inherit a secret owner. */
    fun hydrateProvider(
        provider: ProviderSetting,
        connectionId: String,
        profileIds: Map<Uuid, String>,
        read: (CredentialSlot) -> String,
    ): ProviderSetting {
        val clean = sanitizeProvider(provider)
        val models = clean.models.map { model ->
            val overwrite = model.providerOverwrite ?: return@map model
            val owner = requireNotNull(profileIds[model.id]) { "模型凭据所属资源不存在" }
            model.copy(providerOverwrite = attachAll(overwrite.copyProvider(models = emptyList()), owner, true, read))
        }
        return attachAll(clean.copyProvider(models = models), connectionId, false, read)
    }

    /** Call only with a hydrated details draft or an explicitly imported new provider. Empty values
     * here are deliberate values, not evidence that a secret-free list projection cleared a key.
     */
    fun credentialValues(
        provider: ProviderSetting,
        connectionId: String,
        profileIds: Map<Uuid, String>,
    ): Map<CredentialSlot, String> = buildMap {
        putAll(providerCredentials(provider, connectionId, false))
        provider.models.forEach { model -> model.providerOverwrite?.let { overwrite ->
            val owner = requireNotNull(profileIds[model.id]) { "模型凭据所属资源不存在" }
            putAll(providerCredentials(overwrite, owner, true))
        } }
    }

    fun requiredCredentialSlots(profile: ModelProfile): Set<CredentialSlot> {
        val (provider, model) = projection(profile)
        val override = model.providerOverwrite
        return setOf(requiredSlot(override ?: provider,
            if (override == null) ModelCatalog.credentialId(profile) else profile.id, override != null))
    }

    /** Uses the captured, resolved profile only. Override replaces the parent transport entirely. */
    fun request(profile: ModelProfile, read: (CredentialSlot) -> String): NativeModelRequest {
        require(profile.model.isNotBlank()) { "请先填写模型 ID" }
        val (parent, originalModel) = projection(profile)
        val overwrite = originalModel.providerOverwrite
        val selected = overwrite ?: parent
        require(NativeProviderConfiguration.validHttpsAddress(effectiveBaseUrl(selected))) {
            "模型实际连接地址必须是有效的 HTTPS 地址"
        }
        val slot = requiredSlot(selected,
            if (overwrite == null) ModelCatalog.credentialId(profile) else profile.id, overwrite != null)
        val secret = read(slot)
        val authenticated = if (usesServiceAccount(selected)) {
            selected as ProviderSetting.Google
            require(selected.serviceAccountEmail.isNotBlank() && selected.projectId.isNotBlank() &&
                selected.location.isNotBlank()) { "Vertex 服务账号的邮箱、项目和位置尚未填写完整" }
            require(secret.isNotBlank()) { "所选模型的 Vertex 服务账号尚未填写私钥" }
            selected.copy(apiKey = "", privateKey = secret)
        } else {
            require(secret.isNotBlank()) { "所选模型的 API 连接尚未填写密钥" }
            withApiKey(selected, secret)
        }
        val model = originalModel.copy(modelId = profile.model.trim(),
            providerOverwrite = if (overwrite == null) null else authenticated.copyProvider(models = emptyList()))
        return NativeModelRequest(authenticated.copyProvider(models = listOf(model)), model)
    }

    /** Original serializer legality is checked before admission; diagnostics never contain payloads. */
    fun validate(library: Library): List<Issue> = buildList {
        library.connections.forEach { connection ->
            if (connection.nativeProvider != null) try {
                provider(connection, nativeId("connection", connection.id), emptyList())
            } catch (_: IllegalArgumentException) {
                add(Issue("connections.${connection.id}.nativeProvider", "原版提供商配置无法读取，请检查配置类型与选项"))
            }
        }
        library.profiles.forEach { profile ->
            if (profile.nativeModel != null) try {
                model(profile, nativeId("profile", profile.id))
            } catch (_: IllegalArgumentException) {
                add(Issue("profiles.${profile.id}.nativeModel", "原版模型配置无法读取，请检查配置类型与选项"))
            }
        }
    }

    private fun projection(profile: ModelProfile): NativeModelRequest {
        val model = model(profile, nativeId("profile", profile.id))
        val connection = ApiConnection(id = ModelCatalog.credentialId(profile), name = profile.name,
            protocol = profile.protocol, baseUrl = profile.baseUrl.trimEnd('/'), responseApi = profile.responseApi,
            nativeProvider = profile.resolvedProvider)
        return NativeModelRequest(provider(connection, nativeId("connection", connection.id), listOf(model)), model)
    }

    private fun requiredSlot(provider: ProviderSetting, owner: String, model: Boolean): CredentialSlot =
        CredentialSlot(owner, when {
            usesServiceAccount(provider) && model -> CredentialKind.MODEL_PRIVATE_KEY
            usesServiceAccount(provider) -> CredentialKind.PRIVATE_KEY
            model -> CredentialKind.MODEL_API_KEY
            else -> CredentialKind.API_KEY
        })

    private fun usesServiceAccount(provider: ProviderSetting): Boolean =
        provider is ProviderSetting.Google && provider.vertexAI && provider.useServiceAccount

    private fun effectiveBaseUrl(provider: ProviderSetting): String = when (provider) {
        is ProviderSetting.OpenAI -> provider.baseUrl
        is ProviderSetting.Google -> provider.baseUrl
        is ProviderSetting.Claude -> provider.baseUrl
    }

    private fun providerCredentials(provider: ProviderSetting, owner: String, model: Boolean): Map<CredentialSlot, String> =
        buildMap {
            val api = when (provider) {
                is ProviderSetting.OpenAI -> provider.apiKey
                is ProviderSetting.Google -> provider.apiKey
                is ProviderSetting.Claude -> provider.apiKey
            }
            put(CredentialSlot(owner, if (model) CredentialKind.MODEL_API_KEY else CredentialKind.API_KEY), api)
            if (provider is ProviderSetting.Google)
                put(CredentialSlot(owner, if (model) CredentialKind.MODEL_PRIVATE_KEY else CredentialKind.PRIVATE_KEY), provider.privateKey)
        }

    private fun attachAll(provider: ProviderSetting, owner: String, model: Boolean, read: (CredentialSlot) -> String): ProviderSetting {
        val keyed = withApiKey(provider, read(CredentialSlot(owner,
            if (model) CredentialKind.MODEL_API_KEY else CredentialKind.API_KEY)))
        return if (keyed is ProviderSetting.Google) keyed.copy(privateKey = read(CredentialSlot(owner,
            if (model) CredentialKind.MODEL_PRIVATE_KEY else CredentialKind.PRIVATE_KEY))) else keyed
    }

    private fun withApiKey(provider: ProviderSetting, secret: String): ProviderSetting = when (provider) {
        is ProviderSetting.OpenAI -> provider.copy(apiKey = secret)
        is ProviderSetting.Google -> provider.copy(apiKey = secret)
        is ProviderSetting.Claude -> provider.copy(apiKey = secret)
    }

    private fun cleanProvider(value: JsonObject, dropModels: Boolean): JsonObject = JsonObject(buildMap {
        value.forEach { (key, child) -> when (key) {
            "apiKey", "privateKey", "_formatVersion" -> Unit
            "models" -> if (!dropModels) put(key, JsonArray((child as JsonArray).map {
                cleanModel(it.jsonObject, dropProviderModels = false)
            }))
            else -> put(key, child)
        } }
    })

    private fun cleanModel(value: JsonObject, dropProviderModels: Boolean): JsonObject = JsonObject(buildMap {
        value.forEach { (key, child) -> when (key) {
            "_formatVersion" -> Unit
            "providerOverwrite" -> put(key, if (child == JsonNull) child
                else cleanProvider(child.jsonObject, dropModels = dropProviderModels))
            else -> put(key, child)
        } }
    })

    private fun envelope(value: JsonObject): JsonObject = JsonObject(value +
        ("_formatVersion" to JsonPrimitive(NativeProviderConfiguration.FORMAT_VERSION)))

    private fun withoutEnvelope(value: JsonObject): Map<String, JsonElement> = value - "_formatVersion"
    private fun encodeProvider(provider: ProviderSetting): JsonObject = json.encodeToJsonElement(ProviderSetting.serializer(), provider).jsonObject
    private fun encodeModel(model: Model): JsonObject = json.encodeToJsonElement(Model.serializer(), model).jsonObject
    private fun decodeProvider(value: JsonObject): ProviderSetting = json.decodeFromJsonElement(ProviderSetting.serializer(), value)
    private fun decodeModel(value: JsonObject): Model = json.decodeFromJsonElement(Model.serializer(), value)
    private fun nativeId(kind: String, id: String): Uuid =
        if (kind == "connection" && id.startsWith("provider:"))
            runCatching { Uuid.parse(id.removePrefix("provider:")) }.getOrNull()
                ?: Uuid.parse(UUID.nameUUIDFromBytes("companion-native-provider:$kind:$id".toByteArray(Charsets.UTF_8)).toString())
        else Uuid.parse(UUID.nameUUIDFromBytes("companion-native-provider:$kind:$id".toByteArray(Charsets.UTF_8)).toString())

    private inline fun <T> safe(message: String, block: () -> T): T = try { block() }
        catch (_: Exception) { throw IllegalArgumentException(message) }
}
