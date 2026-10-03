package dev.gray.companion.core

import kotlinx.serialization.Serializable

@Serializable enum class HistorySourceKind { DISPLAY, MODEL_RAW }
@Serializable enum class HistoryAnchor { START, END, PERCENT }
/** Range points address individual BODY messages, one-based. Insertion points address gaps. */
@Serializable data class HistoryPoint(val anchor: HistoryAnchor = HistoryAnchor.START, val value: Double = 1.0)
@Serializable data class HistoryRange(val from: HistoryPoint = HistoryPoint(), val through: HistoryPoint = HistoryPoint(HistoryAnchor.END, 1.0))
@Serializable data class HistorySpeakerRule(val speaker: Role, val apiRole: Role? = null, val prefix: String? = null, val suffix: String? = null)
@Serializable data class HistoryFloorRule(val messageId: String, val apiRole: Role? = null, val prefix: String? = null, val suffix: String? = null)
@Serializable data class HistoryInsertion(val id: String = newId(), val title: String = "历史插入", val template: String = "", val role: Role = Role.SYSTEM, val at: HistoryPoint = HistoryPoint(HistoryAnchor.END, 0.0), val enabled: Boolean = true)
fun defaultHistoryProcessorWorkflow(): Workflow = Workflow(
    title = "历史上下文处理", trigger = RuntimeEvents.HISTORY_PROCESS, enabled = false,
    nodes = listOf(WorkflowNode(title = "处理历史", inputTemplate = "{{history.pending}}", outputKey = "history_summary", includeHistory = false,
        blocks = listOf(PromptBlock(title = "历史处理要求",
            template = "将提供的历史整理为供后续对话使用的摘要，保留人物、世界状态与未完成事项。输出摘要正文。"))))
)
@Serializable data class HistoryProcessor(val workflow: Workflow = defaultHistoryProcessorWorkflow(), val minimumNewFloors: Int = 40, val keepRecentFloors: Int = 12, val outputKey: String = "history_summary", val sourceKind: HistorySourceKind = HistorySourceKind.DISPLAY)
@Serializable data class HistoryConfig(
    val range: HistoryRange = HistoryRange(), val apiRole: Role? = null, val mergeMessages: Boolean = false,
    val prefix: String = "", val suffix: String = "", val floorPrefix: String = "", val floorSuffix: String = "",
    val speakers: List<HistorySpeakerRule> = emptyList(), val floors: List<HistoryFloorRule> = emptyList(),
    val insertions: List<HistoryInsertion> = emptyList(), val processor: HistoryProcessor = HistoryProcessor()
)
/** Projection only. Original chat messages remain the authoritative archive. */
@Serializable data class HistoryDigest(val text: String, val throughMessageId: String, val coveredFloors: Int, val workflowId: String, val createdAt: Long = System.currentTimeMillis())

