package dev.gray.companion.core

import kotlinx.serialization.Serializable

@Serializable enum class CardDataMode { MANUAL, XML }
@Serializable enum class CardFieldKind { TEXT, NUMBER, PROGRESS, LIST }
@Serializable enum class CardFloorScope { LATEST, EACH }
@Serializable data class CardSection(val id: String, val title: String, val initiallyExpanded: Boolean = false)
@Serializable data class CardElement(val id: String = "element_" + newId(), val fieldKey: String, val title: String = "", val sectionId: String = "")
@Serializable data class CardResource(val id: String = newId(), val enabled: Boolean = true,
    val sourceTemplate: String = "", val definition: CardDefinition = CardDefinition(defaultRegion = CardRegion.CHAT))
@Serializable enum class CardPresentation { CARD, SIDE_PANEL, STRIP }
@Serializable data class CardFieldDefinition(
    val key: String,
    val title: String,
    val source: String = "",
    val pattern: String = "",
    val captureGroup: Int = 0,
    val kind: CardFieldKind = CardFieldKind.TEXT,
    val minimum: Double = 0.0,
    val maximum: Double = 100.0,
    val compactLimit: Int = 3,
    val xmlPath: String = ""
)
@Serializable data class CardDefinition(
    val title: String = "新卡片",
    val minimumWidthDp: Int = 280,
    val fields: List<CardFieldDefinition> = emptyList(),
    val presentation: CardPresentation = CardPresentation.CARD,
    val css: String = "",
    val elements: List<CardElement> = emptyList(),
    val defaultRegion: CardRegion? = null,
    val userAdjustable: Boolean = true,
    val autoAdapt: Boolean = true,
    val floorScope: CardFloorScope = CardFloorScope.LATEST,
    val hideWhenEmpty: Boolean = true,
    val condition: Condition? = null,
    val dataMode: CardDataMode = CardDataMode.MANUAL,
    val xmlRoot: String = "status",
    val xmlSample: String = "",
    val sections: List<CardSection> = emptyList()
)
@Serializable enum class CardRegion { CHAT, SIDEBAR, INPUT, MENU }
/** Session preference only: absent placement means the card is in the unplaced tray. */
@Serializable data class CardPlacement(
    val nodeId: String,
    val region: CardRegion,
    val order: Int = 0,
    val collapsed: Boolean = false,
    val hidden: Boolean = false
)
/** Accepted values and their presentation travel together through existing result receipts. */
@Serializable data class CardSnapshotField(
    val key: String,
    val title: String,
    val kind: CardFieldKind = CardFieldKind.TEXT,
    val value: String = "",
    val items: List<String> = emptyList(),
    val minimum: Double = 0.0,
    val maximum: Double = 100.0,
    val compactLimit: Int = 3
)
@Serializable data class CardSnapshot(
    val formatVersion: Int = 1,
    val nodeId: String,
    val title: String,
    val minimumWidthDp: Int = 280,
    val fields: List<CardSnapshotField> = emptyList(),
    val presentation: CardPresentation = CardPresentation.CARD,
    /** The accepted primary BODY revision available when this card was defined. */
    val bodySource: HistorySourceRef? = null,
    val css: String = "",
    val elements: List<CardElement> = emptyList(),
    val defaultRegion: CardRegion? = null,
    val userAdjustable: Boolean = true,
    val autoAdapt: Boolean = true,
    val floorScope: CardFloorScope = CardFloorScope.LATEST,
    val hideWhenEmpty: Boolean = true,
    val condition: Condition? = null,
    val sections: List<CardSection> = emptyList()
)
data class ConfiguredCard(
    val resourceId: String,
    val workflowId: String,
    val nodeId: String,
    val title: String,
    val enabled: Boolean,
    val definition: CardDefinition,
    val outputKey: String,
    val sourceTemplate: String? = null
)
data class ResolvedCard(
    val card: ConfiguredCard,
    val snapshot: CardSnapshot? = null,
    val waiting: Boolean = false,
    val error: String? = null,
    val acceptedTurnId: String? = null,
    val valueCopiedFromEarlierVersion: Boolean = false
)
