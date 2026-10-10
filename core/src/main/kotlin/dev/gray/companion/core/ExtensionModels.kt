package dev.gray.companion.core

import kotlinx.serialization.Serializable

@Serializable enum class ExtensionResourceKind { CHARACTER, MAIN, PACKAGE, GROUP }
@Serializable data class ExtensionResourceRef(val kind: ExtensionResourceKind, val id: String)
@Serializable enum class ExtensionField {
    CHARACTER_DESCRIPTION, ENTRY_TEXT, ENTRY_ENABLED, BLOCK_TEMPLATE, BLOCK_ROLE, BLOCK_ENABLED,
    WORKFLOW_MODEL, WORKFLOW_ENABLED, WORKFLOW_QUEUED, WORKFLOW_PRIORITY,
    NODE_ENABLED, NODE_MODEL, NODE_TEMPERATURE, NODE_TOP_P, NODE_MAX_TOKENS, NODE_REASONING, NODE_ARGUMENT,
    GROUP_STATE_DEFAULT
}
@Serializable data class ExtensionFieldRef(
    val kind: ExtensionResourceKind, val resourceId: String, val field: ExtensionField,
    val entryId: String? = null, val workflowId: String? = null, val nodeId: String? = null,
    val blockId: String? = null, val argDescriptorKey: String? = null
)
@Serializable data class ExtensionParameter(val id: String = newId(), val title: String, val target: ExtensionFieldRef)
@Serializable data class PluginCode(val runtime: String = "javascript", val version: Int = 1, val source: String = "")
/** Native Memory owns rules; its actual calls remain ordinary referenced group workflows. */
@Serializable data class MemoryPluginRules(
    val groupId: String,
    val workflowId: String,
    val aggregationWorkflowId: String? = null,
    val outputKey: String = "history_summary",
    val sourceKind: HistorySourceKind = HistorySourceKind.DISPLAY,
    val minimumNewFloors: Int = 40,
    val keepRecentFloors: Int = 12,
    val thresholdBasis: HistoryThresholdBasis = HistoryThresholdBasis.ELIGIBLE_BLOCK,
    val protectFirstFloors: Int = 0,
    val maximumBatchFloors: Int? = null
)
@Serializable data class ConfigurationExtension(
    val id: String = newId(), val name: String,
    val resources: List<ExtensionResourceRef> = emptyList(), val parameters: List<ExtensionParameter> = emptyList(),
    val code: PluginCode? = null,
    val memory: MemoryPluginRules? = null,
    /** Null is the historical session participation contract; live definitions use explicit opt-in. */
    val enabled: Boolean? = null,
    /** Only legacy main-owned cards need a scope; new extensions are available to every main. */
    val mainScopeId: String? = null
)
/** Read-only legacy wire metadata. Canonical live main calls no longer install collections. */
@Serializable data class ExtensionBinding(val id: String = newId(), val extensionId: String, val enabled: Boolean = false)
@Serializable data class ExtensionBindingOrigin(
    val kind: ExtensionResourceKind, val resourceId: String, val manual: Boolean = false,
    val ownerBindingIds: List<String> = emptyList()
)
data class ExtensionEditResult(val library: Library?, val issues: List<Issue> = emptyList(), val changes: List<String> = emptyList(), val receipt: ConfigurationEditReceipt? = null)
data class ExtensionFieldDescriptor(
    val target: ExtensionFieldRef, val title: String, val type: ActionFieldType = ActionFieldType.STRING,
    val required: Boolean = false, val nullable: Boolean = false, val integer: Boolean = false,
    val min: Double? = null, val max: Double? = null, val choices: List<ActionChoice> = emptyList(),
    val issueKey: String? = null
)
