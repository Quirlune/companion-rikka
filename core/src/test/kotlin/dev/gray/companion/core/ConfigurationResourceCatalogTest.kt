package dev.gray.companion.core

import org.junit.Assert.*
import org.junit.Test

class ConfigurationResourceCatalogTest {
    private fun base() = LibraryFormat.upgrade(Library())

    @Test fun catalogueKeepsEveryPublicKindAndArchiveArrayInOrderWithTypedRoundTrip() {
        val source = base().copy(
            characters = listOf(CharacterCard(id = "c")), mainCalls = listOf(MainCall(id = "m")),
            packages = listOf(PromptPackage(id = "p")), groups = listOf(CallGroup(id = "g")),
            personas = listOf(UserPersona(id = "u")), promptTags = listOf(PromptTag(id = "t")),
            profiles = listOf(ModelProfile(id = "r")), connections = listOf(ApiConnection(id = "a")),
            extensions = listOf(ConfigurationExtension(id = "e", name = "Example"))
        )
        assertEquals(listOf("characters", "mainCalls", "packages", "groups", "personas", "tags", "profiles", "connections", "extensions"),
            ResourceKindCatalog.ordered.map { it.array })
        var copied = base()
        for (kind in ResourceKindCatalog.ordered) {
            val image = kind.values(source).single()
            val id = (image as kotlinx.serialization.json.JsonObject)["id"]!!.toString().trim('"')
            copied = kind.replace(copied, id, image, null, "resources.${kind.array}[0]")
            assertEquals(image, kind.find(copied, id))
        }
        assertEquals(ResourceKindCatalog.arrays, ResourceKindCatalog.folders.entries.associate { it.value to it.key })
    }

    @Test fun malformedResourcesReportSafeNestedFieldLocations() {
        val cases = listOf(
            """{"resources":{"characters":[{"id":"c","entries":[{"id":"e","unexpected":"secret-body"}]}]}}""" to "config.resources.characters[0].entries[0].unexpected",
            """{"resources":{"characters":[{"id":"c","entries":"secret-body"}]}}""" to "config.resources.characters[0].entries",
            """{"resources":{"profiles":[{"id":"p","protocol":"secret-body"}]}}""" to "config.resources.profiles[0].protocol",
            """{"resources":{"profiles":[{"id":"p","customBody":[{"key":"x"}]}]}}""" to "config.resources.profiles[0].customBody[0].value",
            """{"commands":[{"op":"put","kind":"character","value":{"id":"c","entries":"secret-body"}}]}""" to "config.commands[0].value.entries",
            """{"commands":[{"op":"select","key":"reasoningParsing","value":{"mode":"secret-body"}}]}""" to "config.reasoningParsing.mode"
        )
        for ((tail, expected) in cases) {
            val text = tail.replaceFirst("{", "{\"format\":\"companion.config.v1\",")
            val issue = ConfigurationCommands.preview(text, base()).issues.single()
            assertEquals(expected, issue.path)
            assertFalse(issue.message.contains("secret-body"))
            assertFalse(issue.toString().contains("secret-body"))
        }
    }

    @Test fun additionalValidationChecksNormalizedPreviewApplyAndUndoCandidatesOnce() {
        val original = base()
        val text = """{"format":"companion.config.v1","resources":{"tags":[{"id":"tag","name":"新分类"}]}}"""
        var calls = 0
        val validation: (Library) -> List<Issue> = { candidate ->
            calls++
            if (candidate.promptTags.any { it.id == "tag" }) listOf(Issue("native.payload", "固定拒绝信息")) else emptyList()
        }
        val rejected = ConfigurationCommands.preview(text, original, validation)
        assertNull(rejected.plan)
        assertEquals(1, calls)
        val preview = ConfigurationCommands.preview(text, original)
        val plan = requireNotNull(preview.plan)
        val rejectedApply = ConfigurationCommands.apply(plan, original, validation)
        assertNull(rejectedApply.library)
        assertEquals(2, calls)
        val applied = ConfigurationCommands.apply(plan, original)
        val receipt = requireNotNull(applied.receipt)
        val rejectUndo: (Library) -> List<Issue> = { candidate ->
            calls++
            if (candidate.promptTags.none { it.id == "tag" }) listOf(Issue("native.payload", "固定拒绝信息")) else emptyList()
        }
        assertNull(ConfigurationCommands.undo(receipt, requireNotNull(applied.library), rejectUndo).library)
        assertEquals(3, calls)
    }
}
