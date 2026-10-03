package dev.gray.companion.core

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

class ConfigurationCommandsTest {
    private fun emptyLibrary() = LibraryFormat.upgrade(Library())

    @Test fun oldUndoImagesUseAppendedDefaultsButStillDetectLaterMemoryEdits() {
        val base = LibraryFormat.upgrade(Library(mainCalls = listOf(MainCall(id = "main", name = "Old"))))
        val preview = ConfigurationCommands.preview("""{"format":"companion.config.v1","commands":[
            {"op":"mergePatch","kind":"mainCall","id":"main","patch":{"name":"New"}}]}""", base)
        val applied = ConfigurationCommands.apply(requireNotNull(preview.plan), base)
        assertTrue(applied.issues.toString(), applied.issues.none { it.error })
        fun oldImage(image: JsonElement?): JsonElement? {
            val main = image as? JsonObject ?: return image
            val history = main["history"] as JsonObject
            val processor = history["processor"] as JsonObject
            return JsonObject(main + ("history" to JsonObject(history + ("processor" to JsonObject(processor.filterKeys {
                it !in setOf("thresholdBasis", "protectFirstFloors", "maximumBatchFloors", "aggregationWorkflow")
            })))))
        }
        val receipt = requireNotNull(applied.receipt).let { it.copy(changes = it.changes.map { change ->
            change.copy(before = oldImage(change.before), after = oldImage(change.after))
        }) }
        assertEquals(base, ConfigurationCommands.undo(receipt, requireNotNull(applied.library)).library)
        val later = applied.library!!.let { library -> library.copy(mainCalls = library.mainCalls.map { main ->
            main.copy(history = main.history.copy(processor = main.history.processor.copy(protectFirstFloors = 7)))
        }) }
        assertTrue(ConfigurationCommands.undo(receipt, later).issues.any { it.error })
    }

    @Test fun resourcePackageImportsAndBindsAsOnePlan() {
        val base = emptyLibrary()
        val text = """
            {
              "format":"companion.config.v1", "label":"城镇配置",
              "resources":{
                "characters":[{"id":"card", "namespace":"hero", "name":"旅人", "entries":[]}],
                "mainCalls":[{"id":"main", "name":"对话"}],
                "packages":[{"id":"pack", "namespace":"town", "name":"城镇", "entries":[]}],
                "groups":[{"id":"group", "name":"旁白", "workflows":[]}]
              },
              "commands":[
                {"op":"bind", "kind":"character", "id":"card", "packages":["pack"]},
                {"op":"bind", "kind":"mainCall", "id":"main", "packages":["pack"], "groups":["group"]}
              ]
            }
        """.trimIndent()
        val preview = ConfigurationCommands.preview(text, base)
        assertTrue(preview.issues.toString(), preview.issues.isEmpty())
        assertNotNull(preview.plan)
        assertEquals(4, preview.changes.size)
        assertTrue(preview.changes.all { it.operation == "create" })
        val applied = ConfigurationCommands.apply(preview.plan!!, base)
        assertTrue(applied.issues.toString(), applied.issues.isEmpty())
        val library = applied.library!!
        assertEquals(listOf("pack"), library.characters.single().boundPackageIds)
        assertEquals(listOf("group"), library.mainCalls.single().boundGroupIds)
        assertTrue(library.characters.single().entries.isNotEmpty())
        val receipt = applied.receipt!!
        assertEquals("城镇配置", receipt.label)
        val reverted = ConfigurationCommands.undo(receipt, library)
        assertTrue(reverted.issues.toString(), reverted.issues.isEmpty())
        assertEquals(base, reverted.library)
    }

    @Test fun patchUndoRetainsUnrelatedLaterResourceAndDetectsTouchedConflict() {
        val base = emptyLibrary().copy(packages = listOf(
            PromptPackage(id = "pack", namespace = "world", name = "旧世界", slug = "world")))
        val text = """
            {"format":"companion.config.v1", "commands":[
              {"op":"mergePatch", "kind":"package", "slug":"world", "patch":{"name":"新世界"}}
            ]}
        """.trimIndent()
        val preview = ConfigurationCommands.preview(text, base)
        assertTrue(preview.issues.toString(), preview.issues.isEmpty())
        assertEquals(listOf("name"), preview.changes.single().changedFields)
        val applied = ConfigurationCommands.apply(preview.plan!!, base)
        val saved = applied.library!!
        val receipt = applied.receipt!!
        assertEquals("新世界", saved.packages.single().name)
        val later = saved.copy(promptTags = listOf(PromptTag(id = "tag", name = "新增", slug = "new_tag")))
        val undone = ConfigurationCommands.undo(receipt, later)
        val restored = undone.library!!
        assertEquals("旧世界", restored.packages.single().name)
        assertEquals(later.promptTags, restored.promptTags)
        val editedTarget = later.copy(packages = listOf(later.packages.single().copy(name = "用户后来编辑")))
        val conflict = ConfigurationCommands.undo(receipt, editedTarget)
        assertNull(conflict.library)
        assertTrue(conflict.issues.any { it.error && it.message.contains("后续编辑") })
    }

    @Test fun undoCannotRemoveResourceNewConversationNowUses() {
        val base = LibraryFormat.upgrade(emptyLibrary().copy(characters = listOf(
            CharacterCard(id = "card", namespace = "hero", name = "角色"))).withStandardCharacterFields())
        val text = """
            {"format":"companion.config.v1", "resources":{
              "packages":[{"id":"pack", "namespace":"lore", "name":"设定", "entries":[]}]
            }}
        """.trimIndent()
        val preview = ConfigurationCommands.preview(text, base)
        assertTrue(preview.issues.toString(), preview.issues.isEmpty())
        val applied = ConfigurationCommands.apply(preview.plan!!, base)
        val later = applied.library!!.copy(sessions = listOf(Session(id = "later", characterId = "card", packageIds = listOf("pack"))),
            selectedSessionId = "later")
        val undo = ConfigurationCommands.undo(applied.receipt!!, later)
        assertNull(undo.library)
        assertTrue(undo.issues.any { it.error && it.message.contains("提示词包不存在") })
        assertEquals("pack", later.sessions.single().packageIds.single())
    }

    @Test fun nativeResourcePreviewRejectsExistingIdButDeduplicatesIdenticalTag() {
        val tag = PromptTag(id = "tag", name = "类别", slug = "category")
        val base = emptyLibrary().copy(promptTags = listOf(tag))
        val source = emptyLibrary().copy(promptTags = listOf(tag), packages = listOf(
            PromptPackage(id = "new_pack", namespace = "lore", name = "设定", entries = emptyList())))
        val preview = ConfigurationCommands.previewResources(source, base)
        assertTrue(preview.issues.toString(), preview.issues.isEmpty())
        assertEquals(listOf("new_pack"), preview.changes.map { it.id })
        val collision = ConfigurationCommands.previewResources(source.copy(packages = listOf(
            PromptPackage(id = "taken", namespace = "another", name = "覆盖"))),
            base.copy(packages = listOf(PromptPackage(id = "taken", namespace = "existing", name = "已有"))))
        assertNull(collision.plan)
        assertTrue(collision.issues.any { it.error && it.message.contains("ID 已存在") })
    }

    @Test fun strictFieldsAndSettingsHaveConcretePreview() {
        val base = emptyLibrary().copy(profiles = listOf(ModelProfile(id = "model", name = "模型", model = "example")))
        val text = """
            {"format":"companion.config.v1", "commands":[
              {"op":"select", "key":"selectedProfileId", "value":"model"}
            ]}
        """.trimIndent()
        val preview = ConfigurationCommands.preview(text, base)
        assertTrue(preview.issues.toString(), preview.issues.isEmpty())
        assertEquals("selectedProfileId", preview.settings.single().key)
        val applied = ConfigurationCommands.apply(preview.plan!!, base)
        val saved = applied.library!!
        assertEquals("model", saved.selectedProfileId)
        val undone = ConfigurationCommands.undo(applied.receipt!!, saved)
        assertTrue(undone.issues.toString(), undone.issues.isEmpty())
        assertNull(undone.library!!.selectedProfileId)

        val unknown = ConfigurationCommands.preview(
            """{"format":"companion.config.v1","resources":{"packages":[{"id":"x","namespace":"x","name":"X","apiKey":"secret"}]}}""", base)
        assertNull(unknown.plan)
        assertTrue(unknown.issues.any { it.error })
    }
}
