package dev.gray.companion.core

import dev.gray.companion.core.PromptReferenceLanguage.Cardinality
import dev.gray.companion.core.PromptReferenceLanguage.Code
import dev.gray.companion.core.PromptReferenceLanguage.LegacyReference
import dev.gray.companion.core.PromptReferenceLanguage.QueryPlan
import dev.gray.companion.core.PromptReferenceLanguage.SourceScope
import dev.gray.companion.core.PromptReferenceLanguage.SourceSpan
import dev.gray.companion.core.PromptReferenceLanguage.VersionSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptReferenceLanguageTest {
    private fun query(source: String): QueryPlan {
        val result = PromptReferenceLanguage.compile(source)
        assertTrue(result.diagnostics.toString(), result.diagnostics.isEmpty())
        assertTrue(result.reference is QueryPlan)
        return result.reference as QueryPlan
    }

    private fun rejected(source: String, code: Code): PromptReferenceLanguage.CompileResult {
        val result = PromptReferenceLanguage.compile(source)
        assertNull(result.reference)
        assertTrue("$source: ${result.diagnostics}", result.diagnostics.any { it.code == code })
        assertTrue(result.diagnostics.all { it.span.start >= 0 &&
            it.span.endExclusive >= it.span.start && it.span.endExclusive <= source.length })
        return result
    }

    @Test fun fieldOrderWhitespaceAndWrapperDoNotChangeThePlan() {
        val first = query("type:pack.in:world.under:lore/north.name:muyao.tag:npc.tag:ally.any:awake,alive.not:spoiler.at:version-2.xml:\"bio/ns:summary.text\"")
        val second = query("  {{ xml : \"bio/ns:summary.text\" . at:version-2 . tag:ally . name:muyao . in:world . not:spoiler . any:alive, awake . under:lore/north . tag:npc . type:pack }}  ")
        assertEquals(first, second)
        assertEquals(1, first.languageVersion)
        assertEquals(Cardinality.ONE, first.cardinality)
        assertEquals(listOf("bio", "ns:summary.text"), first.xml)
        assertEquals(listOf("lore", "north"), first.folder!!.path)
        assertTrue(first.folder.includeDescendants)
    }

    @Test fun tagGroupsRemainDistinctAndRepeatedExclusionsMerge() {
        val plan = query("type:pack.in:world.tag:npc.tag:ally.tag:npc.any:warm,calm.not:spoiler.not:hidden,spoiler")
        assertEquals(setOf("ally", "npc"), plan.requiredTags)
        assertEquals(setOf("calm", "warm"), plan.anyTags)
        assertEquals(setOf("hidden", "spoiler"), plan.excludedTags)
        assertEquals(plan, query("in:world.type:pack.not:spoiler,hidden.any:calm,warm.tag:ally.tag:npc"))
        rejected("type:pack.in:world.any:warm.any:calm", Code.DUPLICATE_FIELD)
        rejected("type:pack.in:world.tag:npc,ally", Code.INVALID_VALUE)
        rejected("type:pack.in:world.any:warm,", Code.EXPECTED_VALUE)
        rejected("type:pack.in:world.not:", Code.EXPECTED_VALUE)
    }

    @Test fun sourceCapabilitiesAndCallOwnershipAreCheckedWithoutAResourceLibrary() {
        query("type:character.in:hero.folder:identity.name:bio")
        query("type:main.in:daily.call:status.name:current.xml:status/value")
        query("type:group.in:extras.call:status.name:current")
        query("type:builtin.in:history.name:messages.at:latest")
        for (source in listOf(
            "type:pack.in:world.call:status.name:current",
            "type:main.in:daily.folder:status",
            "type:group.in:extras.under:status",
            "type:character.in:hero.folder:identity.under:lore",
            "type:builtin.in:history.name:messages.xml:text"
        )) rejected(source, Code.UNSUPPORTED_FIELD)
        rejected("type:main.in:daily.name:current", Code.MISSING_FIELD)
        rejected("type:group.in:extras.name:current", Code.MISSING_FIELD)
    }

    @Test fun cardinalityIsDeclaredByNameAndWildcardCannotPretendToBeSingle() {
        assertEquals(Cardinality.ONE, query("type:pack.in:world.name:muyao").cardinality)
        val selection = query("type:pack.in:*.tag:npc")
        assertEquals(Cardinality.MANY, selection.cardinality)
        assertEquals(SourceScope.Loaded, selection.source)
        assertEquals(Cardinality.MANY, query("type:main.in:daily.call:status").cardinality)
        rejected("type:pack.in:*.name:muyao", Code.AMBIGUOUS_SCOPE)
        rejected("type:pack.in:world.at:latest", Code.UNSUPPORTED_FIELD)
        rejected("type:pack.in:world.xml:bio", Code.UNSUPPORTED_FIELD)
    }

    @Test fun versionSelectorsHaveExplicitBoundsAndBuiltinKeepsLatestOnly() {
        val base = "type:pack.in:world.name:muyao"
        assertEquals(VersionSelector.Latest, query(base).at)
        assertEquals(VersionSelector.Turn(0), query("$base.at:turn-0").at)
        assertEquals(VersionSelector.Update(Int.MAX_VALUE), query("$base.at:update-2147483647").at)
        assertEquals(VersionSelector.Version(1), query("$base.at:version-1").at)
        for (selector in listOf("version-0", "turn--1", "update-2147483648", "previous"))
            rejected("$base.at:$selector", Code.INVALID_VALUE)
        rejected("type:builtin.in:history.name:messages.at:turn-0", Code.UNSUPPORTED_FIELD)
        rejected("$base@version-1", Code.MIXED_SYNTAX)
        rejected("$base#bio", Code.MIXED_SYNTAX)
    }

    @Test fun unknownMissingAndRepeatedFieldsNeverProduceAPartialPlan() {
        rejected("type:pack", Code.MISSING_FIELD)
        rejected("in:world", Code.MISSING_FIELD)
        rejected("type:unknown.in:world", Code.INVALID_VALUE)
        rejected("type:pack.in:world.match:npc", Code.UNKNOWN_FIELD)
        rejected("type:pack.in:\"world\"", Code.INVALID_VALUE)
        rejected("type:pack.in:World", Code.INVALID_VALUE)
        rejected("type:pack.in:world.under:lore//north", Code.INVALID_VALUE)
        val source = "{{ type:pack.in:world.type:character }}"
        val result = rejected(source, Code.DUPLICATE_FIELD)
        val duplicate = result.diagnostics.single { it.code == Code.DUPLICATE_FIELD }
        assertEquals("type", duplicate.field)
        assertEquals("type:character", source.substring(duplicate.span.start, duplicate.span.endExclusive))
    }

    @Test fun legacyNamespacesUnicodeAndVersionSemanticsStayWithTheLegacyParser() {
        for (expression in listOf("character.bio#ns:tag", "pack.world.木遥@version-1#bio",
            "result.score@version-0", "category.lore#bio", "history", "input")) {
            val result = PromptReferenceLanguage.compile("  {{ $expression }}  ")
            assertTrue(result.diagnostics.toString(), result.diagnostics.isEmpty())
            assertEquals(LegacyReference(expression), result.reference)
            assertTrue(result.fields.isEmpty())
        }
        rejected("{{unknown}}", Code.INVALID_LEGACY)
        rejected("{{character.bio", Code.INVALID_WRAPPER)
    }

    @Test fun quotedXmlRetainsSeparatorsAndDiagnosticsUseOriginalUtf16Offsets() {
        val source = "  {{ type:pack.in:world.name:muyao.xml:\"bio/ns:tag.with-dot\" }}  "
        val result = PromptReferenceLanguage.compile(source)
        assertTrue(result.diagnostics.toString(), result.diagnostics.isEmpty())
        val xml = result.fields.single { it.name == "xml" }
        val literal = "\"bio/ns:tag.with-dot\""
        assertEquals(SourceSpan(source.indexOf(literal), source.indexOf(literal) + literal.length), xml.values.single())
        assertEquals(literal, source.substring(xml.values.single().start, xml.values.single().endExclusive))
        rejected("type:pack.in:world.name:muyao.xml:ns:tag", Code.INVALID_VALUE)
        rejected("type:pack.in:world.name:muyao.xml:\"bio", Code.UNCLOSED_QUOTE)
        val escape = "type:pack.in:world.name:muyao.xml:\"bio\\q\""
        val invalidEscape = rejected(escape, Code.INVALID_ESCAPE).diagnostics.single()
        assertEquals("\\q", escape.substring(invalidEscape.span.start, invalidEscape.span.endExclusive))
        val unicode = "{{type:pack.in:world.name:muyao.xml:\"😀\"}}"
        val invalidXml = rejected(unicode, Code.INVALID_VALUE).diagnostics.single()
        assertEquals("xml:\"😀\"", unicode.substring(invalidXml.span.start, invalidXml.span.endExclusive))
    }

    @Test fun finiteInputAndMalformedBoundariesFailBeforeReturningAReference() {
        rejected(" ", Code.EMPTY_REFERENCE)
        rejected("{{ }}", Code.EMPTY_REFERENCE)
        rejected("type:pack.in:world.", Code.EXPECTED_FIELD)
        rejected("type:pack.in:world..tag:npc", Code.EXPECTED_FIELD)
        rejected("type:pack.in:world any:npc", Code.EXPECTED_SEPARATOR)
        val short = "type:pack.in:world"
        val exactLimit = short.padEnd(PromptReferenceLanguage.MAX_REFERENCE_LENGTH, ' ')
        assertEquals(query(short), query(exactLimit))
        rejected(exactLimit + " ", Code.TOO_LONG)
        assertFalse(PromptReferenceLanguage.compile("{{ $short }} trailing").diagnostics.isEmpty())
    }
}
