package me.rerere.rikkahub.ui.pages.setting.components

/** Presentation capabilities. Original RikkaHub entry points retain every existing control by default. */
data class ProviderConfigureOptions(
    val apiPaths: Boolean = true,
    val historyReasoning: Boolean = true,
    val claudeCache: Boolean = true,
    val googleVertex: Boolean = true,
)

data class ProviderModelOptions(
    val typesAndCapabilities: Boolean = true,
    val advancedSettings: Boolean = true,
    val customRequestBody: Boolean = true,
    val builtInTools: Boolean = true,
    val editModelId: Boolean = false,
    val prepareNewOverride: (me.rerere.ai.provider.ProviderSetting) -> me.rerere.ai.provider.ProviderSetting = { it },
)

enum class ProviderModelLoadPolicy { AUTOMATIC, MANUAL, DISABLED }

data class ProviderDetailOptions(
    val configure: ProviderConfigureOptions = ProviderConfigureOptions(),
    val model: ProviderModelOptions = ProviderModelOptions(),
    val share: Boolean = true,
    val balance: Boolean = true,
    val connectionTest: Boolean = true,
    val saveToast: Boolean = true,
    val modelLoadPolicy: ProviderModelLoadPolicy = ProviderModelLoadPolicy.AUTOMATIC,
    val retainConfigDraft: Boolean = false,
    val balanceLoadPolicy: ProviderModelLoadPolicy = ProviderModelLoadPolicy.AUTOMATIC,
)
