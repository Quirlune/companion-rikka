package dev.gray.companion.core

import kotlin.math.floor

/** A captured, read-only projection of BODY chat messages for one main call. */
class HistoryContext(
    private val library: Library,
    private val session: Session,
    private val main: MainCall,
    private val processing: Boolean = false,
    private val capturedPending: Pending? = null
) {
    data class Pending(
        val messages: List<ChatMessage>, val text: String, val throughMessageId: String,
        val coveredFloors: Int, val newFloors: Int
    )

    val isProcessing: Boolean get() = processing
    private val floors = if (processing && capturedPending != null) capturedPending.messages
        else session.messages.filter { it.outputSlot == OutputSlot.BODY && !it.deleted }
    private val archivedFloors = session.messages.filter { it.outputSlot == OutputSlot.BODY && !it.deleted }
    private val digest = session.historyDigests[main.id]?.takeIf { it.coveredFloors in 1..archivedFloors.size &&
        archivedFloors[it.coveredFloors - 1].id == it.throughMessageId &&
        it.workflowId == main.history.processor.workflow.id }
    private val selected: IntRange = if (processing) range(HistoryRange(), floors.size)
        else range(main.history.range, floors.size)
    private val selectedFloors = if (selected.isEmpty()) emptyList() else floors.slice(selected)
    private val pending = capturedPending ?: candidate()
    private val speakerRules = main.history.speakers.associateBy { it.speaker }
    private val floorRules = main.history.floors.associateBy { it.messageId }
    private val characterName = library.characters.firstOrNull { it.id == session.characterId }?.name ?: "助手"
    private val wrapperToken = Regex("\\{\\{\\s*(speaker(?:\\.name)?|floor)\\s*\\}\\}")

    fun pendingCandidate(): Pending? = pending
    fun containsMessage(id: String?): Boolean = id != null && selectedFloors.any { it.id == id }
    fun withMessages(messages: List<ChatMessage>): HistoryContext = HistoryContext(
        library, session.copy(messages = messages), main, processing, capturedPending
    )

    fun read(key: String, renderInsertion: (String) -> String = { it }): String? = when (key) {
        "raw" -> rawText(selectedFloors)
        "text" -> projected(renderInsertion).joinToString("\n") { it.text }
        "pending" -> pending?.text.orEmpty()
        "summary" -> digest?.text.orEmpty()
        "count" -> selectedFloors.size.toString()
        "pending.count" -> (pending?.newFloors ?: 0).toString()
        else -> null
    }

    fun projected(renderInsertion: (String) -> String = { it }): List<ChatMessage> {
        val insertions = main.history.insertions.filter { it.enabled }.groupBy { gap(it.at, selectedFloors.size) }
        val useDigest = digest != null && !processing && selected.first == 0 && selected.last >= digest.coveredFloors - 1 &&
            insertions.keys.none { it in 1 until digest.coveredFloors }
        val output = mutableListOf<ChatMessage>()
        var cursor = 0
        while (cursor <= selectedFloors.size) {
            insertions[cursor].orEmpty().forEach { insertion ->
                output += ChatMessage(role = insertion.role, text = renderInsertion(insertion.template))
            }
            if (cursor == selectedFloors.size) break
            if (useDigest && cursor == 0) {
                output += ChatMessage(role = main.history.apiRole ?: Role.SYSTEM, text = digest!!.text,
                    id = "history-digest:${main.id}:${digest.throughMessageId}")
                cursor = digest.coveredFloors
            } else {
                val originalIndex = selected.first + cursor
                output += wrapped(selectedFloors[cursor], originalIndex + 1)
                cursor++
            }
        }
        if (output.isEmpty()) return output
        val prefix = main.history.prefix
        val suffix = main.history.suffix
        if (prefix.isNotEmpty()) output[0] = output[0].copy(text = prefix + output[0].text)
        if (suffix.isNotEmpty()) output[output.lastIndex] = output.last().copy(text = output.last().text + suffix)
        return if (main.history.mergeMessages && output.size > 1) listOf(ChatMessage(
            role = main.history.apiRole ?: Role.SYSTEM, text = output.joinToString("\n") { it.text }
        )) else output
    }

    private fun wrapped(message: ChatMessage, number: Int): ChatMessage {
        val speaker = speakerRules[message.role]
        val specific = floorRules[message.id]
        val role = specific?.apiRole ?: speaker?.apiRole ?: main.history.apiRole ?: message.role
        val prefix = specific?.prefix ?: speaker?.prefix ?: main.history.floorPrefix
        val suffix = specific?.suffix ?: speaker?.suffix ?: main.history.floorSuffix
        return message.copy(role = role, text = wrapper(prefix, message, number) + message.text + wrapper(suffix, message, number))
    }

    private fun wrapper(template: String, original: ChatMessage, floorNumber: Int): String =
        wrapperToken.replace(template) { match -> when (match.groupValues[1]) {
            "speaker.name" -> original.speakerName ?: when (original.role) {
                Role.USER -> library.userName(session)
                Role.ASSISTANT -> characterName
                Role.SYSTEM -> "系统"
            }
            "speaker" -> original.role.name.lowercase()
            else -> floorNumber.toString()
        } }

    private fun candidate(): Pending? {
        val config = main.history.processor
        if (!config.workflow.enabled || processing || floors.isEmpty()) return null
        val currentUser = floors.indexOfLast { it.role == Role.USER && it.id == MacroReferences.currentTurnId(session) }
        if (currentUser < 0) return null
        val eligible = floors.subList(0, currentUser)
        var cutoff = (eligible.size - config.keepRecentFloors).coerceAtLeast(0)
        if (cutoff < eligible.size && cutoff > 0 && eligible[cutoff].role != Role.USER) {
            cutoff = (cutoff downTo 0).firstOrNull { eligible[it].role == Role.USER } ?: 0
        }
        val covered = digest?.coveredFloors ?: 0
        val newFloors = cutoff - covered
        if (newFloors < config.minimumNewFloors || newFloors <= 0) return null
        val source = buildList {
            digest?.let { add(ChatMessage(role = Role.SYSTEM, text = it.text)) }
            addAll(eligible.subList(covered, cutoff))
        }
        return Pending(source, rawText(source), eligible[cutoff - 1].id, cutoff, newFloors)
    }

    private fun rawText(messages: List<ChatMessage>): String = messages.joinToString("\n") { "${it.role.name.lowercase()}: ${it.text}" }

    companion object {
        fun rangeValid(range: HistoryRange, count: Int): Boolean = count == 0 ||
            endpoint(range.from, count) <= endpoint(range.through, count) ||
            endpoint(range.from, count) >= count || endpoint(range.through, count) < 0
        private fun range(range: HistoryRange, count: Int): IntRange {
            if (count == 0) return IntRange.EMPTY
            val first = endpoint(range.from, count)
            val last = endpoint(range.through, count)
            val visibleFirst = maxOf(0, first)
            val visibleLast = minOf(count - 1, last)
            return if (visibleFirst <= visibleLast) visibleFirst..visibleLast else IntRange.EMPTY
        }
        private fun endpoint(point: HistoryPoint, count: Int): Int = when (point.anchor) {
            HistoryAnchor.START -> point.value.toInt() - 1
            HistoryAnchor.END -> count - point.value.toInt()
            HistoryAnchor.PERCENT -> floor((count - 1) * point.value / 100.0).toInt().coerceIn(0, count - 1)
        }
        private fun gap(point: HistoryPoint, count: Int): Int = when (point.anchor) {
            HistoryAnchor.START -> point.value.toInt().coerceIn(0, count)
            HistoryAnchor.END -> (count - point.value.toInt()).coerceIn(0, count)
            HistoryAnchor.PERCENT -> floor(count * point.value / 100.0).toInt().coerceIn(0, count)
        }
    }
}
