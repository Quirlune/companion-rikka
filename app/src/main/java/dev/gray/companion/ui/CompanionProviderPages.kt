package dev.gray.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.dokar.sonner.ToastType
import dev.gray.companion.*
import dev.gray.companion.core.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.uuid.Uuid
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.rikkahub.ui.context.LocalToaster
import me.rerere.rikkahub.ui.pages.assistant.detail.CustomHeaders
import me.rerere.rikkahub.ui.pages.setting.SettingProviderPageContent
import me.rerere.rikkahub.ui.pages.setting.SettingProviderDetailContent
import me.rerere.rikkahub.ui.pages.setting.components.*
import okhttp3.OkHttpClient
import org.koin.compose.koinInject

private val companionConfigure = ProviderConfigureOptions()
private val companionDetail = ProviderDetailOptions(
    configure = companionConfigure,
    model = ProviderModelOptions(editModelId = true,
        prepareNewOverride = { NativeProviderCodec.sanitizeProvider(it).copyProvider(models = emptyList()) }),
    saveToast = false, balanceLoadPolicy = ProviderModelLoadPolicy.MANUAL,
    modelLoadPolicy = ProviderModelLoadPolicy.MANUAL, retainConfigDraft = true)

private fun profileMapping(snapshot: CompanionProviderSnapshot, provider: ProviderSetting): Map<Uuid, String> =
    snapshot.profileIds + provider.models.associate { it.id to (snapshot.profileIds[it.id] ?: "profile:${it.id}") }

/** Only presentation adapters; the actual list and detail layouts are the original pages. */
@Composable
internal fun CompanionProvidersPage(library: Library, busy: Boolean,
    onCommit: suspend (CompanionProviderPlan) -> List<Issue>,
    onOpenProvider: (Uuid) -> Unit, onBack: () -> Unit) {
    val snapshot = remember(library.connections, library.profiles) { CompanionProviderAdapter.capture(library) }
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    var committing by remember { mutableStateOf(false) }
    fun submit(desired: List<ProviderSetting>, complete: (Boolean) -> Unit = {}) {
        if (busy || committing) { complete(false); return }
            val keys = desired.filter { it.id !in snapshot.connectionIds }.flatMap { provider ->
                NativeProviderCodec.credentialValues(provider, "provider:${provider.id}", profileMapping(snapshot, provider))
                    .map { (slot, value) -> slot to ("" to value) }
            }.toMap()
            val result = CompanionProviderAdapter.plan(snapshot, desired.map(NativeProviderCodec::sanitizeProvider),
                editedCredentials = keys)
            val plan = result.plan
            if (plan == null) {
                toaster.show(result.issues.firstOrNull()?.message ?: "修改无效", type = ToastType.Error)
                complete(false)
            }
            else {
                committing = true
                scope.launch {
                    try {
                        val issues = onCommit(plan)
                        issues.firstOrNull { it.error }?.let { toaster.show(it.message, type = ToastType.Error) }
                        complete(issues.none { it.error })
                    } catch (failure: Exception) {
                        complete(false)
                        throw failure
                    } finally { committing = false }
                }
            }
    }
    SettingProviderPageContent(snapshot.providers, onProvidersChange = { submit(it) },
        onProvidersChangeConfirmed = { desired, complete -> submit(desired, complete) },
        onOpenProvider = onOpenProvider, onBack = onBack,
        allowRecommendations = true, allowImport = true, configureOptions = companionConfigure,
        enabled = !busy && !committing)
}

@Composable
internal fun CompanionProviderDetailPage(providerId: String, library: Library, busy: Boolean,
    getCredential: (CredentialSlot) -> String, onCommit: suspend (CompanionProviderPlan) -> List<Issue>,
    onEditProfile: (String) -> Unit, onSelectDefault: (String) -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val toaster = LocalToaster.current
    val context = LocalContext.current.applicationContext
    val client = koinInject<OkHttpClient>()
    val latestLibrary by rememberUpdatedState(library)
    var snapshot by remember(providerId) { mutableStateOf(CompanionProviderAdapter.capture(library)) }
    val nativeId = remember(providerId, snapshot.connectionIds) {
        runCatching { Uuid.parse(providerId) }.getOrNull()?.takeIf { it in snapshot.connectionIds }
            ?: snapshot.providerId(providerId)
    }
    val connectionId = nativeId?.let(snapshot.connectionIds::get)
    if (nativeId == null || connectionId == null) {
        Column { SettingsHeader("API 连接", onBack); Text("连接已不存在，请返回提供商列表。", Modifier.padding(16.dp)) }
        return
    }
    fun readStored(image: CompanionProviderSnapshot): Map<CredentialSlot, String> = buildSet {
        add(CredentialSlot(connectionId)); add(CredentialSlot(connectionId, CredentialKind.PRIVATE_KEY))
        image.profiles.filter { it.apiConnectionId == connectionId }.forEach {
            add(CredentialSlot(it.id, CredentialKind.MODEL_API_KEY)); add(CredentialSlot(it.id, CredentialKind.MODEL_PRIVATE_KEY))
        }
    }.associateWith(getCredential)
    var storedSecrets by remember(connectionId) { mutableStateOf(readStored(snapshot)) }
    var extras by remember(connectionId) { mutableStateOf(snapshot.extras.getValue(nativeId)) }
    var configDirty by remember(connectionId) { mutableStateOf(false) }
    var committing by remember(connectionId) { mutableStateOf(false) }
    var discard by remember(connectionId) { mutableStateOf(false) }
    val extrasDirty = extras != snapshot.extras[nativeId]
    val dirty = configDirty || extrasDirty
    val editingEnabled = !busy && !committing
    fun requestBack() { if (editingEnabled) { if (dirty) discard = true else onBack() } }
    BackHandler { requestBack() }

    // Updating another resource must not replace the connection form's unsaved provider image.
    LaunchedEffect(library.connections, library.profiles, dirty, committing) {
        if (!dirty && !committing) {
            snapshot = CompanionProviderAdapter.capture(library)
            snapshot.extras[nativeId]?.let { extras = it }
            storedSecrets = readStored(snapshot)
        }
    }
    val projected = snapshot.providers.firstOrNull { it.id == nativeId }
    if (projected == null) {
        Column { SettingsHeader("API 连接", onBack); Text("连接已不存在，请返回提供商列表。", Modifier.padding(16.dp)) }
        return
    }
    val provider = remember(projected, storedSecrets) {
        NativeProviderCodec.hydrateProvider(projected, connectionId, snapshot.profileIds) { storedSecrets[it].orEmpty() }
    }
    val requestManager = remember(client, extras.customHeaders) {
        val requestClient = client.newBuilder().apply {
            if (extras.customHeaders.isNotEmpty()) addInterceptor { chain ->
                val request = chain.request().newBuilder()
                extras.customHeaders.forEach { request.header(it.name, it.value) }
                chain.proceed(request.build())
            }
        }.build()
        ProviderManager(requestClient, context)
    }

    fun submit(desired: List<ProviderSetting>, keys: Map<CredentialSlot, Pair<String, String>> = emptyMap(), deleting: Boolean = false,
               complete: (Boolean) -> Unit = {}) {
        if (!editingEnabled) { complete(false); return }
        val result = CompanionProviderAdapter.plan(snapshot, desired,
            connectionExtras = snapshot.extras + (nativeId to extras), editedCredentials = keys)
        val plan = result.plan
        if (plan == null) {
            toaster.show(result.issues.firstOrNull()?.message ?: "修改无效", type = ToastType.Error)
            complete(false)
            return
        }
        committing = true
        scope.launch {
            try {
                val issues = onCommit(plan)
                val failure = issues.firstOrNull { it.error }
                if (failure != null) toaster.show(failure.message, type = ToastType.Error)
                else if (deleting) onBack()
                else {
                    snapshot = CompanionProviderAdapter.capture(latestLibrary.copy(connections = plan.connections, profiles = plan.profiles))
                    extras = snapshot.extras.getValue(nativeId)
                    storedSecrets = readStored(snapshot)
                    configDirty = false
                    toaster.show("已保存", type = ToastType.Success)
                }
                complete(failure == null)
            } catch (failure: Exception) {
                complete(false)
                throw failure
            } finally { committing = false }
        }
    }

    fun submitProvider(edited: ProviderSetting, complete: (Boolean) -> Unit = {}) {
        val keys = NativeProviderCodec.credentialValues(edited, connectionId, profileMapping(snapshot, edited))
            .mapValues { (slot, value) -> storedSecrets[slot].orEmpty() to value }
            .filterValues { it.first != it.second }
        val clean = NativeProviderCodec.sanitizeProvider(edited)
        submit(snapshot.providers.map { if (it.id == nativeId) clean else it }, keys, complete = complete)
    }
    SettingProviderDetailContent(provider = provider,
        onEditConfirmed = { edited, complete ->
            submitProvider(edited, complete)
        },
        onEdit = { edited ->
            submitProvider(edited)
        },
        onDelete = { submit(snapshot.providers.filterNot { it.id == nativeId }, deleting = true) },
        onBack = ::requestBack, options = companionDetail, enabled = editingEnabled,
        modelsEnabled = editingEnabled && !dirty,
        onConfigDirtyChanged = { configDirty = it },
        configExtraContent = {
            ChoiceLine("同时请求", extras.maxConcurrentRequests, listOf(1 to "1 个（串行）", 2 to "最多 2 个")) {
                if (editingEnabled) extras = extras.copy(maxConcurrentRequests = it)
            }
            EditorSection("自定义请求头", "${extras.customHeaders.size} 项") {
                CustomHeaders(extras.customHeaders.map { CustomHeader(it.name, it.value) }) { headers ->
                    if (editingEnabled) extras = extras.copy(customHeaders = headers.map { RequestHeader(it.name, it.value) })
                }
            }
            if (dirty) Text("先保存连接配置，再编辑模型。", style = MaterialTheme.typography.bodySmall)
        },
        modelExtraContent = { model ->
            val profileId = snapshot.profileIds[model.id]
            if (profileId != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (library.selectedProfileId == profileId) Text("全局默认", style = MaterialTheme.typography.labelMedium)
                    else Spacer(Modifier.weight(1f))
                    var menu by remember(model.id) { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }, enabled = editingEnabled && !dirty) { Icon(HugeIcons.MoreVertical, "调用设置") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text("调用参数") }, onClick = { menu = false; onEditProfile(profileId) })
                            DropdownMenuItem(text = { Text("设为全局默认") }, enabled = library.selectedProfileId != profileId,
                                onClick = { menu = false; onSelectDefault(profileId) })
                        }
                    }
                }
            }
        },
        loadModels = { requested ->
            val savedExtras = snapshot.extras.getValue(nativeId)
            withContext(Dispatchers.IO) {
                val queryClient = client.newBuilder().apply {
                    if (savedExtras.customHeaders.isNotEmpty()) addInterceptor { chain ->
                        val request = chain.request().newBuilder()
                        savedExtras.customHeaders.forEach { request.header(it.name, it.value) }
                        chain.proceed(request.build())
                    }
                }.build()
                ProviderManager(queryClient, context).getProviderByType(requested).listModels(requested)
            }
        },
        requestProviderManager = requestManager,
        onRequestError = { failure ->
            if (failure is CancellationException) throw failure
            IllegalStateException("连接请求失败，请检查连接配置。")
        },
        prepareProviderForShare = NativeProviderCodec::sanitizeProvider,
        onModelLoadError = { failure ->
            if (failure is CancellationException) throw failure
            toaster.show("模型列表获取失败，请检查连接配置；也可以手动添加模型。", type = ToastType.Error)
        })

    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("连接有未保存的修改") },
        text = { Text("可以回配置页保存，或放弃修改后返回。") },
        confirmButton = { TextButton(onClick = { discard = false; onBack() }) { Text("放弃并返回") } },
        dismissButton = { TextButton(onClick = { discard = false }) { Text("继续编辑") } })
}
