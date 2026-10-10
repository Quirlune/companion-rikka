package dev.gray.companion.core

import org.junit.Assert.*
import org.junit.Test

class DefaultAndSlugContractTest {
    @Test fun savedChatFreezesCurrentChoicesWithoutPinningTheSource() {
        val main = MainCall(id = "main", name = "old")
        val session = Session(id = "chat", mainCallId = main.id, results = mapOf("answer" to "retained"))
        val library = Library(mainCalls = listOf(main), sessions = listOf(session), chatFolders = listOf(ChatFolder("folder")))
        val saved = ChatArchive.save(library, session.id, "folder")
        assertNull(saved.sessions.single().executableWorld)
        val edited = saved.copy(mainCalls = listOf(main.copy(name = "new")))
        val restored = ChatArchive.restore(edited, saved.savedChats.single().id).sessions.first()
        assertEquals("old", CompletedWorlds.executionLibrary(edited, restored).mainCalls.single().name)
        assertEquals("retained", restored.results["answer"])
        assertTrue(restored.executableWorld!!.recipes.isEmpty())
    }
    @Test fun specialDefaultsEnforceOneModelBodyAndAllowCustomTriggers() {
        val call = Workflow(id = "call", trigger = "custom.event", nodes = listOf(WorkflowNode(id = "node", visible = true)))
        val main = MainCall(id = "main", workflows = listOf(call), primaryWorkflowId = call.id, specialDefault = true)
        assertTrue(DefaultCallRules.issues(main).isEmpty())
        assertTrue(DefaultCallRules.issues(main.copy(workflows = listOf(call.copy(nodes = call.nodes + WorkflowNode())))).isNotEmpty())
        val nonModel = call.copy(nodes = listOf(call.nodes.single().copy(actionId = "text.render")))
        val nonBody = call.copy(nodes = listOf(call.nodes.single().copy(outputSlot = OutputSlot.AFTER_BODY)))
        assertTrue(DefaultCallRules.issues(main.copy(workflows = listOf(nonModel))).isNotEmpty())
        assertTrue(DefaultCallRules.issues(main.copy(workflows = listOf(nonBody))).isNotEmpty())
        assertTrue(DefaultCallRules.issues(main.copy(specialDefault = false, workflows = listOf(call.copy(nodes = call.nodes + WorkflowNode())))).isEmpty())
    }
    @Test fun slugResultsResolveStableProducerAndKeepLegacyAddresses() {
        val node = WorkflowNode(id = "node", slug = "answer", outputKey = "opaque_key", visible = true)
        val call = Workflow(id = "call", slug = "reply", nodes = listOf(node))
        val main = MainCall(id = "main", slug = "chat", workflows = listOf(call), primaryWorkflowId = call.id)
        val library = LibraryFormat.upgrade(Library(mainCalls = listOf(main)))
        val origin = ResultOrigin(main.id, call.id, node.id)
        val session = Session(mainCallId = main.id, results = mapOf(node.outputKey to "saved"), resultOrigins = mapOf(node.outputKey to origin))
        val expression = "{{type:main.in:chat.call:reply.name:answer}}"
        assertEquals("saved", MacroResolver(library, session).render(expression))
        assertEquals("saved", MacroResolver(library, session).render("{{result.opaque_key}}"))
        val masked = session.copy(unavailableResultKeys = setOf(node.outputKey),
            currentOnlyMacros = mapOf("result.opaque_key" to MacroHead(2, origin)),
            macroHistory = mapOf("result.opaque_key" to listOf(MacroVersion(1, "past", origin))))
        assertEquals("past", MacroResolver(library, masked).render(expression.removeSuffix("}}") + ".at:version-1}}"))
        assertEquals("", MacroResolver(library, session.copy(resultOrigins = mapOf(node.outputKey to origin.copy(nodeId = "another")))).render(expression))
        assertTrue(MacroResolver(library, session, executionVersion = 8).apply { render(expression) }.issues.any { it.error })
        assertEquals(node.id, ResourceLocations.resolveReference(library, null, expression).single().nodeId)
        assertTrue(ResourceUsageIndex.usages(library.copy(mainCalls = library.mainCalls.map { it.copy(bodyTextTemplate = expression) }),
            ResourceLocation(ExtensionResourceRef(ExtensionResourceKind.MAIN, main.id))).any { it.kind == ResourceUseKind.TEMPLATE })
    }
}
