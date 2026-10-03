package dev.gray.companion.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowRequirementsTest {
    @Test fun credentialsFollowRegisteredCapabilitiesAndEnabledSelectedWorkflows() {
        val descriptors = ActionRegistry.defaults().descriptors() + ActionDescriptor("custom.model", "Custom model", usesModel = true)
        val program = Workflow(id = "program", nodes = listOf(WorkflowNode(actionId = "text.render")))
        val model = Workflow(id = "model", nodes = listOf(WorkflowNode(actionId = "custom.model")))
        val group = CallGroup(id = "group", workflows = listOf(model))
        val session = Session(id = "session", mainCallId = "main")
        val library = Library(mainCalls = listOf(MainCall(id = "main", workflows = listOf(program))), groups = listOf(group))
        assertFalse(WorkflowRequirements.requiresModel(library, session, descriptors))
        assertTrue(WorkflowRequirements.requiresModel(library, session.copy(groupIds = listOf(group.id)), descriptors))
        assertFalse(WorkflowRequirements.requiresModel(library.copy(groups = listOf(group.copy(workflows = listOf(model.copy(enabled = false))))), session.copy(groupIds = listOf(group.id)), descriptors))
        assertFalse(WorkflowRequirements.requiresModel(library.copy(mainCalls = listOf(MainCall(id = "main", workflows = listOf(model.copy(nodes = model.nodes.map { it.copy(enabled = false) }))))), session, descriptors))
        assertTrue(WorkflowRequirements.requiresModel(library.copy(mainCalls = listOf(MainCall(id = "main", workflows = listOf(model)))), session, descriptors))
    }
}
