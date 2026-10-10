package dev.gray.companion.core

import org.junit.Assert.*
import org.junit.Test

class MemoryDraftEditingTest {
    private fun fixture(): Library {
        val initial = LibraryDefaults.create().let { library ->
            library.copy(profiles = library.profiles.map { it.copy(model = "offline") })
        }
        val result = GlobalMemory.ensureGlobal(initial)
        assertNotNull(result.issues.toString(), result.library)
        return requireNotNull(result.library)
    }

    @Test fun sourceEditsToOtherStageAndIntermediateNodeSurviveSave() {
        var library = fixture()
        val initial = requireNotNull(GlobalMemory.draft(library))
        val aggregate = requireNotNull(initial.processor.aggregationWorkflow)
        val intermediate = WorkflowNode(id = "extra-transform", slug = "extra_transform", actionId = "text.render",
            outputKey = "extra_transform", inputTemplate = "preserved", enabled = false)
        library = GlobalMemory.withDraft(library, initial.copy(processor = initial.processor.copy(
            aggregationWorkflow = aggregate.copy(nodes = listOf(intermediate) + aggregate.nodes))))
        val baseline = requireNotNull(GlobalMemory.draft(library))
        val groupRef = ExtensionResourceRef(ExtensionResourceKind.GROUP, baseline.group.id)
        val block = baseline.largeNode.blocks.first()
        val blockField = ExtensionFieldRef(groupRef.kind, groupRef.id, ExtensionField.BLOCK_TEMPLATE,
            workflowId = aggregate.id, nodeId = baseline.largeNode.id, blockId = block.id)
        val first = ExtensionFieldRegistry.writeDraft(GlobalMemory.withDraft(library, baseline), blockField,
            "Changed through another stage's source editor", block.template)
        assertNotNull(first.issues.toString(), first.library)
        val nodeField = ExtensionFieldRef(groupRef.kind, groupRef.id, ExtensionField.NODE_ENABLED,
            workflowId = aggregate.id, nodeId = intermediate.id)
        val second = ExtensionFieldRegistry.writeDraft(requireNotNull(first.library), nodeField, "true", "false")
        assertNotNull(second.issues.toString(), second.library)
        val adopted = requireNotNull(GlobalMemory.adoptSourceDraft(baseline, requireNotNull(second.library)))
        assertEquals(baseline.group, adopted.group)
        assertEquals(baseline.extension, adopted.extension)
        val result = GlobalMemory.update(library, baseline, adopted)
        assertNotNull(result.issues.toString(), result.library)
        val saved = requireNotNull(GlobalMemory.draft(requireNotNull(result.library)))
        assertEquals("Changed through another stage's source editor", saved.largeNode.blocks.first().template)
        val savedIntermediate = saved.processor.aggregationWorkflow!!.nodes.first { it.id == intermediate.id }
        assertTrue(savedIntermediate.enabled)
        assertEquals("preserved", savedIntermediate.inputTemplate)
    }

    @Test fun sourceAdoptionRejectsOwnerReplacementAndSaveStillRejectsStaleBaseline() {
        val library = fixture()
        val baseline = requireNotNull(GlobalMemory.draft(library))
        val candidate = GlobalMemory.withDraft(library, baseline.copy(smallNode = baseline.smallNode.copy(
            blocks = baseline.smallNode.blocks.map { it.copy(template = "edited") })))
        val adopted = requireNotNull(GlobalMemory.adoptSourceDraft(baseline, candidate))
        assertNull(GlobalMemory.adoptSourceDraft(baseline, candidate.copy(memoryConfig = baseline.config.copy(enabled = !baseline.config.enabled))))
        assertNull(GlobalMemory.adoptSourceDraft(baseline, candidate.copy(groups = candidate.groups.map {
            if (it.id == baseline.group.id) it.copy(name = "replaced owner metadata") else it
        })))
        val stale = library.copy(groups = library.groups.map {
            if (it.id == baseline.group.id) it.copy(name = "concurrent change") else it
        })
        assertNull(GlobalMemory.update(stale, baseline, adopted).library)
        assertNull(GlobalMemory.update(library, baseline, adopted.copy(group = adopted.group.copy(name = "forged"))).library)
    }
}
