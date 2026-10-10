package dev.gray.companion.core

import org.junit.Assert.*
import org.junit.Test

class ExtensionFieldRegistryTest {
    @Test fun registeredEditsTargetOnlyTheOwningChildAndSupplyItsIssueLocation() {
        val node = WorkflowNode(id = "node", outputKey = "tone", generation = ModelCallOptions(temperature = 0.1f),
            blocks = listOf(PromptBlock(id = "block", template = "text")))
        val sibling = WorkflowNode(id = "sibling", outputKey = "other")
        val base = LibraryFormat.upgrade(Library(groups = listOf(CallGroup(id = "group", name = "Group",
            workflows = listOf(Workflow(id = "flow", nodes = listOf(node, sibling)))))))
        val temperature = ExtensionFieldRef(ExtensionResourceKind.GROUP, "group", ExtensionField.NODE_TEMPERATURE,
            workflowId = "flow", nodeId = "node")
        val updated = requireNotNull(ExtensionFieldRegistry.write(base, temperature, "0.4", "0.1").library)
        assertEquals("0.4", ExtensionFieldRegistry.read(updated, temperature))
        assertEquals(base.groups.single().workflows.single().nodes.last(), updated.groups.single().workflows.single().nodes.last())
        assertNull(ExtensionFieldRegistry.write(updated, temperature, "0.5", "0.1").library)
        assertEquals(temperature, ResourceLocations.resolveIssue(updated,
            Issue("workflow.flow.node.node.generation.temperature", "invalid"))?.field)

        val role = temperature.copy(field = ExtensionField.BLOCK_ROLE, blockId = "block")
        val roleEdited = requireNotNull(ExtensionFieldRegistry.write(updated, role, "USER", "SYSTEM").library)
        assertEquals("USER", ExtensionFieldRegistry.read(roleEdited, role))
        assertEquals(role, ResourceLocations.resolveIssue(roleEdited, Issue("workflow.flow.node.node.block.block.role", "invalid"))?.field)
    }
}
