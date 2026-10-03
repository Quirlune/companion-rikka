package dev.gray.companion.core

import kotlinx.serialization.Serializable

@Serializable enum class HistoryThresholdBasis { ELIGIBLE_BLOCK, UNCOMPRESSED_FLOORS }
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
@Serializable data class HistoryProcessor(val workflow: Workflow = defaultHistoryProcessorWorkflow(), val minimumNewFloors: Int = 40, val keepRecentFloors: Int = 12, val outputKey: String = "history_summary", val sourceKind: HistorySourceKind = HistorySourceKind.DISPLAY,
    val thresholdBasis: HistoryThresholdBasis = HistoryThresholdBasis.ELIGIBLE_BLOCK,
    val protectFirstFloors: Int = 0, val maximumBatchFloors: Int? = null, val aggregationWorkflow: Workflow? = null)
@Serializable data class HistoryConfig(
    val range: HistoryRange = HistoryRange(), val apiRole: Role? = null, val mergeMessages: Boolean = false,
    val prefix: String = "", val suffix: String = "", val floorPrefix: String = "", val floorSuffix: String = "",
    val speakers: List<HistorySpeakerRule> = emptyList(), val floors: List<HistoryFloorRule> = emptyList(),
    val insertions: List<HistoryInsertion> = emptyList(), val processor: HistoryProcessor = HistoryProcessor()
)
/** Projection only. Original chat messages remain the authoritative archive. */
@Serializable data class HistoryDigest(val text: String, val throughMessageId: String, val coveredFloors: Int, val workflowId: String, val createdAt: Long = System.currentTimeMillis())


/** Includes disabled configurations so IDs, metadata and recovery structure have one enumeration. */
fun MainCall.configuredWorkflows(): List<Workflow> = workflows + history.processor.workflow +
    listOfNotNull(history.processor.aggregationWorkflow)

fun defaultHistoryAggregationWorkflow(processor: HistoryProcessor, batchSize: Int = 5): Workflow {
    require(batchSize >= 2)
    val prefix = "memory_${newId().replace("-", "").take(12)}"
    val selectKey = "${prefix}_ids"
    val readKey = "${prefix}_children"
    val summaryKey = "${prefix}_summary"
    val source = if (processor.sourceKind == HistorySourceKind.MODEL_RAW) "{{history.model}}" else "{{history.display}}"
    val select = WorkflowNode(title = "选择未合并的小摘要", actionId = "history.memory.select", outputKey = selectKey,
        includeHistory = false, inputTemplate = "", recordTrajectory = false, arguments = mapOf(
            "source" to source, "leafWorkflowId" to processor.workflow.id, "mode" to "UNMERGED_LEAVES",
            "count" to batchSize.toString(), "requireCount" to "true"))
    val read = WorkflowNode(title = "读取小摘要", actionId = "history.memory.read", outputKey = readKey,
        includeHistory = false, inputTemplate = "", recordTrajectory = false, dependsOn = listOf(select.id),
        condition = Condition(expression = "exists(macro('result.$selectKey'))"), arguments = mapOf("ids" to "{{result.$selectKey}}"))
    val model = WorkflowNode(title = "合并大摘要", outputKey = summaryKey, inputTemplate = "{{result.$readKey}}",
        includeHistory = false, recordTrajectory = false, dependsOn = listOf(read.id),
        blocks = listOf(PromptBlock(title = "大摘要要求", template = "将这些按时间排列的小摘要整理成一份大摘要，保留人物关系、世界状态、重要变化与未完成事项；不要添加摘要外的信息。只输出摘要正文。")))
    val write = WorkflowNode(title = "关联并保存大摘要", actionId = "history.memory.write", outputKey = "${prefix}_parent",
        inputTemplate = "{{result.$summaryKey}}", includeHistory = false, recordTrajectory = false, dependsOn = listOf(model.id),
        arguments = mapOf("source" to source, "title" to "大摘要", "childrenIds" to "{{result.$selectKey}}"))
    return Workflow(title = "大摘要整理", afterWorkflowId = processor.workflow.id, nodes = listOf(select, read, model, write))
}
