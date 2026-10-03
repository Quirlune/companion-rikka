package dev.gray.companion.core

import kotlinx.serialization.Serializable

@Serializable enum class RecognitionMode { ALL, ANY }
@Serializable enum class RecognitionValueType { TEXT, NUMBER }
@Serializable data class RecognitionRule(
    val template: String = "{{input}}", val pattern: String = "", val captureGroup: Int = 0,
    val op: Compare = Compare.EXISTS, val value: String = "", val negate: Boolean = false,
    val valueType: RecognitionValueType = RecognitionValueType.TEXT
)
@Serializable data class Recognition(
    val mode: RecognitionMode = RecognitionMode.ALL, val rules: List<RecognitionRule> = emptyList(),
    val groups: List<Recognition> = emptyList(), val negate: Boolean = false
)
// Activation changes are also versions of the value's shape.
/** null firstTurn means an omitted interval whose start cannot be established. */
@Serializable data class MacroGap(val firstTurn: Int? = null)

/** Current-only metadata. Results and state keep their text in the existing runtime maps. */
@Serializable data class MacroHead(
    val version: Int, val origin: ResultOrigin, val enabled: Boolean = true,
    val turn: Int? = null, val turnId: String? = null, val valueOrigin: ResultOrigin? = null,
    val gap: MacroGap = MacroGap(), val text: String? = null
)

@Serializable data class MacroVersion(
    val version: Int, val text: String, val origin: ResultOrigin, val enabled: Boolean = true,
    val turn: Int? = null, val turnId: String? = null, val valueOrigin: ResultOrigin? = null,
    val precedingGap: MacroGap? = null
)
@Serializable enum class InjectionScope { TURN, SESSION }
@Serializable data class PromptInjection(
    val id: String, val block: PromptBlock, val origin: ResultOrigin,
    val targetWorkflowId: String? = null, val targetNodeId: String? = null,
    val scope: InjectionScope = InjectionScope.TURN, val turnId: String? = null
)
@Serializable enum class MacroTileKind { TEXT, NUMBER, PROGRESS, STATUS, HTML }
@Serializable enum class MacroTilePlacement { CHAT, SIDEBAR }
@Serializable enum class MacroHtmlValueKind { TEXT, NUMBER }
@Serializable data class MacroHtmlBinding(
    val name: String = "", val pattern: String = "", val captureGroup: Int = 0,
    val kind: MacroHtmlValueKind = MacroHtmlValueKind.TEXT
)
@Serializable data class MacroStatusField(
    val key: String = "", val title: String = "", val kind: MacroTileKind = MacroTileKind.TEXT,
    val minimum: Double = 0.0, val maximum: Double = 100.0
)
@Serializable data class MacroTileStyle(
    val foreground: String = "", val background: String = "", val fontSize: Int = 14,
    val cornerRadius: Int = 12, val widthPercent: Int = 100
)
@Serializable data class MacroTile(
    val id: String = newId(), val title: String = "新磁贴", val template: String = "",
    val kind: MacroTileKind = MacroTileKind.TEXT, val slot: OutputSlot = OutputSlot.AFTER_BODY,
    val minimum: Double = 0.0, val maximum: Double = 100.0, val pattern: String = "",
    val captureGroup: Int = 0, val enabled: Boolean = true, val hideWhenEmpty: Boolean = true,
    val style: MacroTileStyle = MacroTileStyle(),
    val placement: MacroTilePlacement = MacroTilePlacement.CHAT,
    val fields: List<MacroStatusField> = emptyList(),
    val htmlTemplate: String = "", val htmlCss: String = "",
    val htmlBindings: List<MacroHtmlBinding> = emptyList()
)
