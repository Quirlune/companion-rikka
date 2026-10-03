package dev.gray.companion.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacroReferencesTest {
    @Test fun previousTurnAndPreviousUpdateAreDifferentAndNeverExpandWholeHistory() {
        val main = MainCall(id = "main", name = "Main")
        val origin = ResultOrigin(main.id, "flow", "writer")
        val versions = listOf(
            MacroVersion(1, "first", origin, turn = 1),
            MacroVersion(2, "second", origin, turn = 1),
            MacroVersion(3, "third", origin, turn = 3)
        )
        val session = Session(mainCallId = main.id, messages = (1..3).map { ChatMessage(id = "u$it", role = Role.USER) },
            results = mapOf("note" to "third"), resultOrigins = mapOf("note" to origin),
            macroHistory = mapOf("result.note" to versions))
        val library = Library(mainCalls = listOf(main))
        val resolver = MacroResolver(library, session)
        assertEquals("third|second|second|first|", resolver.render("{{macro.note}}|{{result.note@turn-1}}|{{macro.note@update-1}}|{{macro.note@version-1}}|{{macro.note@version-99}}"))
        assertTrue(resolver.issues.none { it.error })
        val target = main.workflows.first().nodes.first()
        val literalSession = session.copy(promptInjections = listOf(PromptInjection("literal",
            PromptBlock(template = "literal {{input}} and {{unknown}}"), origin,
            targetWorkflowId = main.workflows.first().id, targetNodeId = target.id, scope = InjectionScope.SESSION)))
        val literalPreview = PromptCompiler().preview(library, literalSession, target.copy(inputTemplate = "", includeHistory = false), "")
        assertEquals("literal {{input}} and {{unknown}}", literalPreview.messages.single().text)
        assertTrue(literalPreview.issues.none { it.error })
        assertEquals("second", MacroResolver(library, session.copy(messages = session.messages.take(2))).render("{{macro.note}}"))
        val legacy = session.copy(macroHistory = emptyMap())
        assertEquals("third|", MacroResolver(library, legacy).render("{{macro.note}}|{{macro.note@turn-1}}"))
    }

    @Test fun activationOverridesPromptAvailabilityAndUnloadingRestoresSourceWithoutDeletingHistory() {
        val pack = PromptPackage(id = "pack", namespace = "world", entries = listOf(PromptEntry(key = "place", text = "source", enabled = false, category = "world")))
        val group = CallGroup(id = "group", boundPackageIds = listOf(pack.id))
        val main = MainCall(id = "main", boundGroupIds = listOf(group.id), boundPackageIds = listOf(pack.id))
        val origin = ResultOrigin(group.id, "flow", "enable")
        val session = Session(mainCallId = main.id, messages = listOf(ChatMessage(role = Role.USER)),
            macroHistory = mapOf("pack.world.place" to listOf(MacroVersion(1, "active", origin, turn = 1))))
        val library = Library(mainCalls = listOf(main), groups = listOf(group), packages = listOf(pack))
        assertEquals("active|active", MacroResolver(library, session).render("{{pack.world.place}}|{{category.world}}"))
        val disabled = session.copy(macroHistory = mapOf("pack.world.place" to listOf(MacroVersion(2, "active", origin, enabled = false, turn = 1))))
        assertEquals("", MacroResolver(library, disabled).render("{{pack.world.place}}"))
        val unloaded = library.copy(mainCalls = listOf(main.copy(boundGroupIds = emptyList())), packages = listOf(pack.copy(entries = pack.entries.map { it.copy(enabled = true) })))
        assertEquals("source", MacroResolver(unloaded, session).render("{{pack.world.place}}"))
        assertEquals(1, session.macroHistory.getValue("pack.world.place").size)
        val otherGroup = CallGroup(id = "other")
        val valueFromUnloadedGroup = session.copy(macroHistory = mapOf("result.secret" to listOf(
            MacroVersion(1, "hidden", origin, turn = 1, valueOrigin = ResultOrigin(otherGroup.id, "old", "old"))
        )))
        assertEquals("", MacroResolver(library.copy(groups = listOf(group, otherGroup)), valueFromUnloadedGroup).render("{{macro.secret}}"))
    }

    @Test fun recognitionCombinesExtractionComparisonAndNestedNegation() {
        val recognition = Recognition(rules = listOf(
            RecognitionRule(template = "score:25", pattern = "score:([0-9]+)", captureGroup = 1, op = Compare.GREATER, value = "20")
        ), groups = listOf(Recognition(mode = RecognitionMode.ANY, rules = listOf(
            RecognitionRule(template = "", op = Compare.EXISTS),
            RecognitionRule(template = "ready", op = Compare.EQUALS, value = "ready")
        ))))
        assertTrue(RecognitionEvaluator.matches(recognition) { it })
        assertFalse(RecognitionEvaluator.matches(recognition.copy(negate = true)) { it })
        assertEquals(null, SafePatterns.validate("score:([0-9]+)", 1))
        assertTrue(SafePatterns.validate("(a)\\1") != null)
    }
}
