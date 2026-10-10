package dev.gray.companion.core

import org.junit.Assert.*
import org.junit.Test

class HistorySourceRoleSelectionTest {
    private val main = MainCall(id = "main", name = "Main")
    private val floors = listOf(
        ChatMessage(id = "system", role = Role.SYSTEM, text = "system"),
        ChatMessage(id = "u1", text = "user one"),
        ChatMessage(id = "a1", role = Role.ASSISTANT, text = "assistant one", rawText = "raw one"),
        ChatMessage(id = "u2", text = "user two"),
        ChatMessage(id = "a2", role = Role.ASSISTANT, text = "assistant two", rawText = "raw two"),
        ChatMessage(id = "a3", role = Role.ASSISTANT, text = "assistant three", rawText = "raw three"),
        ChatMessage(id = "u3", text = "user three"),
        ChatMessage(id = "a4", role = Role.ASSISTANT, text = "assistant four", rawText = "raw four"),
        ChatMessage(id = "current", text = "current input"),
    )
    private fun session() = Session(id = "session", mainCallId = main.id, messages = floors)
    private fun library(selectedMain: MainCall = main) = Library(mainCalls = listOf(selectedMain))

    @Test fun lastThreeMatchingFloorsPreserveOriginalNumbersAndApiRoleIsIndependent() {
        val before = session()
        val selected = HistorySelection(sourceRole = Role.ASSISTANT, rangeExplicit = true,
            range = HistoryRange(HistoryPoint(HistoryAnchor.END, 3.0), HistoryPoint(HistoryAnchor.END, 1.0)),
            format = HistoryReferenceFormat(apiRole = Role.USER, floorPrefix = "{{floor}}/{{speaker}}: "))
        val projected = HistoryViews.project(library(), before, main, selected)
        assertTrue(projected.issues.toString(), projected.issues.isEmpty())
        assertEquals(listOf("a2", "a3", "a4"), projected.sourceIds)
        assertEquals(listOf(Role.USER, Role.USER, Role.USER), projected.messages.map { it.role })
        assertEquals(listOf("5/assistant: assistant two", "6/assistant: assistant three", "8/assistant: assistant four"),
            projected.messages.map { it.text })
        val mappedAgain = HistoryViews.project(library(), before, main, selected.copy(
            format = selected.format!!.copy(apiRole = Role.SYSTEM)))
        assertEquals(projected.sourceIds, mappedAgain.sourceIds)
        assertTrue(mappedAgain.messages.all { it.role == Role.SYSTEM })
        val raw = HistoryViews.project(library(), before, main, selected.copy(
            sourceKind = HistorySourceKind.MODEL_RAW, format = HistoryReferenceFormat()))
        assertEquals(listOf("raw two", "raw three", "raw four"), raw.messages.map { it.text })
        assertEquals(floors, before.messages)
        assertEquals(HistoryMemoryState(), before.historyMemory)

        val restricted = main.copy(history = main.history.copy(range = HistoryRange(
            HistoryPoint(HistoryAnchor.START, 2.0), HistoryPoint(HistoryAnchor.START, 2.0))))
        assertEquals(listOf("u1"), HistoryViews.sources(before, HistorySelection(compressed = true), restricted).map { it.messageId })
        assertEquals(listOf("a1", "a2", "a3", "a4"), HistoryViews.sources(before,
            HistorySelection(compressed = true, sourceRole = Role.ASSISTANT), restricted).map { it.messageId })
        val onlyLatest = selected.copy(range = HistoryRange(HistoryPoint(HistoryAnchor.END, 1.0)),
            sourceKind = HistorySourceKind.MODEL_RAW)
        val missingOldRaw = before.copy(messages = before.messages.map { if (it.id == "a1") it.copy(rawText = null) else it })
        assertEquals(listOf("a4"), HistoryViews.project(library(), missingOldRaw, main, onlyLatest).sourceIds)
    }

    @Test fun capturedSourcesFilterBeforeRangeAndMixedSummaryNeverLeaksExcludedAuthors() {
        val before = session()
        val mixed = HistoryMemory.segment("Mixed", "must not leak assistant details", floors.subList(1, 3),
            main.history.processor.workflow.id)
        val marked = before.copy(historyMemory = HistoryMemory.apply(before.historyMemory,
            HistoryMemoryPatch(segments = listOf(mixed))))
        val saved = marked.copy(historyMemory = HistoryMemory.apply(marked.historyMemory,
            HistoryViews.copy(marked, HistorySelection(), "saved", "Saved", HistorySegmentMode.COMPRESS, listOf(mixed.id), main)))
        val memory = saved.historyMemory
        val users = HistorySelection(viewId = "saved", revision = 1, compressed = true, sourceRole = Role.USER,
            format = HistoryReferenceFormat(apiRole = Role.ASSISTANT, floorPrefix = "{{floor}}: "))
        val projected = HistoryViews.project(library(), saved, main, users)
        assertTrue(projected.issues.toString(), projected.issues.isEmpty())
        assertEquals(listOf("u1", "u2", "u3", "current"), projected.sourceIds)
        assertEquals(listOf("2: user one", "4: user two", "7: user three", "9: current input"), projected.messages.map { it.text })
        assertTrue(projected.messages.all { it.role == Role.ASSISTANT })
        assertFalse(projected.messages.any { it.text.contains(mixed.summary) })
        val latest = users.copy(rangeExplicit = true, range = HistoryRange(
            HistoryPoint(HistoryAnchor.END, 3.0), HistoryPoint(HistoryAnchor.END, 1.0)))
        assertEquals(listOf("u2", "u3", "current"), HistoryViews.sources(saved, latest, main).map { it.messageId })
        val changed = MessageOperations.editText(saved, "u1", "edited live user").session!!
        assertEquals("2: user one", HistoryViews.project(library(), changed, main, users).messages.first().text)
        val copied = HistoryViews.copy(saved, latest, "only_users", "Only users", HistorySegmentMode.EXPAND, emptyList(), main)
        val filteredView = saved.copy(historyMemory = HistoryMemory.apply(saved.historyMemory, copied))
        assertEquals(listOf("u2", "u3", "current"), HistoryViews.sources(filteredView,
            HistorySelection(viewId = "only_users"), main).map { it.messageId })
        assertEquals(memory, saved.historyMemory)
        assertEquals(floors, saved.messages)
    }

    @Test fun speakerGrammarAndLegacyExecutionRejectWithoutFallingBackToAllHistory() {
        val expression = "{{type:builtin.in:history.name:messages.speaker:user}}"
        val selected = HistoryViews.selection(expression, 12)!!
        assertEquals(Role.USER, selected.sourceRole)
        assertNull(HistoryViews.selection(expression, 11))
        assertTrue(macroExpressionError(expression.removeSurrounding("{{", "}}"), 11)!!.contains("原始发言方"))
        assertThrows(IllegalArgumentException::class.java) { HistoryViews.sources(session(), selected, main, 11) }
        assertThrows(IllegalArgumentException::class.java) {
            HistoryViews.copy(session(), selected, "saved", "Saved", HistorySegmentMode.EXPAND, emptyList(), main, executionVersion = 11)
        }
        val rejected = HistoryViews.project(library(), session(), main, selected, executionVersion = 11)
        assertTrue(rejected.messages.isEmpty())
        assertTrue(rejected.sourceIds.isEmpty())
        assertTrue(rejected.issues.any { it.error && it.message.contains("原始发言方") })
        val configured = main.copy(workflows = listOf(Workflow(nodes = listOf(WorkflowNode(
            blocks = listOf(PromptBlock(template = expression)))))))
        assertTrue(LibraryValidator.validate(library(configured), 11).any { it.error && it.message.contains("原始发言方") })
        listOf("all", "tool", "USER", "user,assistant").forEach { speaker ->
            assertNull(HistoryViews.selection(expression.replace("speaker:user", "speaker:$speaker")))
        }
        assertNull(HistoryViews.selection("{{type:pack.in:lore.name:entry.speaker:user}}"))
        val legacy = HistoryViews.selection("{{type:builtin.in:history.name:context}}", 11)!!
        assertNull(legacy.sourceRole)
        assertEquals(floors.map { it.id }, HistoryViews.sources(session(), legacy, main, 11).map { it.messageId })
    }

    @Test fun referenceEditorEncodingKeepsFilterFormatViewAndExplicitRangeIntent() {
        val format = HistoryReferenceFormat(apiRole = Role.ASSISTANT,
            floorPrefix = "[{{floor}} {{speaker.name}}] ",
            speakers = listOf(HistorySpeakerRule(Role.USER, Role.ASSISTANT, "User: ")))
        val selections = listOf(
            HistorySelection(compressed = true, format = format),
            HistorySelection(compressed = true, format = format, rangeExplicit = true),
            HistorySelection(sourceRole = Role.USER, format = format),
            HistorySelection(sourceRole = Role.ASSISTANT, viewId = "saved", revision = 2,
                sourceKind = HistorySourceKind.MODEL_RAW, compressed = true, format = format, rangeExplicit = true,
                range = HistoryRange(HistoryPoint(HistoryAnchor.END, 3.0), HistoryPoint(HistoryAnchor.END, 1.0))),
        )
        selections.forEach { initial ->
            val token = HistoryReferences.expression(initial)
            assertEquals(initial, HistoryViews.selection(token, 12))
            assertEquals(initial.rangeExplicit, token.contains(".from:"))
            assertEquals(initial.sourceRole != null, token.contains(".speaker:"))
        }
        assertEquals(Role.SYSTEM, HistoryViews.selection("{{type:builtin.in:history.name:messages.speaker:system}}")!!.sourceRole)
    }
}
