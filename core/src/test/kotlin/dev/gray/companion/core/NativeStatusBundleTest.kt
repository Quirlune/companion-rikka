package dev.gray.companion.core

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Test

/** Exercises the actual delivered ZIP with the native importer and native action pipeline. */
class NativeStatusBundleTest {
    private val prefix = "gray_status_v1"
    private val stateKey = "${prefix}_previous"
    private fun xml(value: String) = "<status><time>$value</time><location>room</location>" +
        "<mood>calm</mood><relationship>known</relationship><goal>talk</goal>" +
        "<notes><item>retained</item></notes></status>"

    private fun imported(): Library {
        val bundlePath = System.getProperty("companion.statusBundle")
        assumeNotNull(bundlePath)
        val file = File(requireNotNull(bundlePath))
        val read = file.inputStream().use {
            ResourceArchiveImport.read(it, Files.createTempDirectory("status-bundle-check").toFile(), file.name)
        }
        assertTrue(read.issues.toString(), read.issues.none { it.error })
        val base = Library()
        val preview = ConfigurationCommands.preview(requireNotNull(read.document), base)
        assertTrue(preview.issues.toString(), preview.issues.none { it.error })
        val applied = ConfigurationCommands.apply(requireNotNull(preview.plan), base)
        assertTrue(applied.issues.toString(), applied.issues.none { it.error })
        val library = requireNotNull(applied.library)
        assertEquals(1, library.packages.size)
        assertEquals(1, library.groups.size)
        assertEquals(1, library.extensions.size)
        assertTrue(library.mainCalls.isEmpty() && library.profiles.isEmpty() && library.connections.isEmpty() && library.sessions.isEmpty())
        assertFalse(library.groups.single().workflows.single().enabled)
        assertNull(library.extensions.single().code)
        return library
    }

    private fun runnable(imported: Library): Library = LibraryFormat.upgrade(imported.copy(
        characters = listOf(CharacterCard(id = "fixture-character", namespace = "fixture", name = "Fixture")),
        mainCalls = listOf(MainCall(id = "fixture-main", name = "Fixture", specialDefault = true,
            primaryWorkflowId = "fixture-primary", workflows = listOf(Workflow(id = "fixture-primary",
                nodes = listOf(WorkflowNode(id = "fixture-model", outputKey = "fixture-body", visible = true,
                    inputTemplate = "fixture-main-request", includeHistory = false)))))),
        groups = imported.groups.map { it.copy(workflows = it.workflows.map { flow -> flow.copy(enabled = true) }) },
        profiles = listOf(ModelProfile(id = "fixture-profile", model = "offline")), selectedProfileId = "fixture-profile"))

    private fun session() = Session(id = "fixture-session", characterId = "fixture-character", mainCallId = "fixture-main",
        enabledExtensionIds = listOf("${prefix}_extension"), state = mapOf(stateKey to xml("PREVIOUS_STATE")),
        messages = listOf(
            ChatMessage(id = "u0", role = Role.USER, text = "EXCLUDED_U0"),
            ChatMessage(id = "a0", role = Role.ASSISTANT, text = "EXCLUDED_A0", turnId = "u0", workflowId = "fixture-primary"),
            ChatMessage(id = "u1", role = Role.USER, text = "EXCLUDED_U1"),
            ChatMessage(id = "a1", role = Role.ASSISTANT, text = "RECENT_A1", turnId = "u1", workflowId = "fixture-primary"),
            ChatMessage(id = "u2", role = Role.USER, text = "RECENT_U2")))

    @Test fun actualZipImportsAndNativePipelineUsesThreeFloorsPreviousStateAndBoundCard() = runBlocking {
        val library = runnable(imported())
        val session = session()
        var statusRequests = 0
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String {
                if (messages.any { it.text == "fixture-main-request" }) return "CURRENT_MAIN_BODY"
                statusRequests++
                val request = messages.joinToString("\n") { it.text }
                for (required in listOf("RECENT_A1", "RECENT_U2", "CURRENT_MAIN_BODY", "PREVIOUS_STATE"))
                    assertTrue("Missing $required in $request", request.contains(required))
                for (excluded in listOf("EXCLUDED_U0", "EXCLUDED_A0", "EXCLUDED_U1", "首次生成时的状态基线"))
                    assertFalse("Unexpected $excluded in $request", request.contains(excluded))
                assertEquals(1, Regex("RECENT_U2").findAll(request).count())
                return xml("NEXT_STATE")
            }
        }
        val result = WorkflowEngine().run(library, session, "RECENT_U2", gateway)
        assertEquals(result.traces.toString(), RunStatus.SUCCESS, result.status)
        assertEquals(1, statusRequests)
        assertEquals(xml("NEXT_STATE"), result.state[stateKey])
        assertEquals(1, result.replies.size)
        val accepted = session.copy(messages = session.messages + result.replies, state = result.state,
            results = result.results, resultOrigins = result.resultOrigins, macroHistory = result.macroHistory,
            currentOnlyMacros = result.currentOnlyMacros)
        val card = CardDisplays.resolve(library, accepted).single()
        val body = requireNotNull(CardDisplays.currentPrimaryBody(library, accepted))
        assertTrue(CardDisplays.belongsBelowBody(card, body))
        assertEquals("NEXT_STATE", card.snapshot!!.fields.first().value)
        assertEquals(HistoryMemory.source(body), card.snapshot!!.bodySource)
        assertEquals("u2", card.acceptedTurnId)
        assertEquals("u2", accepted.macroHistory.getValue("state.$stateKey").last().turnId)
        assertFalse(CardDisplays.belongsBelowBody(card, body.copy(text = "Edited", activeTextRevisionId = "edited")))
    }

    @Test fun invalidStructuredStatusRetainsPriorStateAndCreatesNoPartialCard() = runBlocking {
        val library = runnable(imported())
        val session = session()
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit) =
                if (messages.any { it.text == "fixture-main-request" }) "CURRENT_MAIN_BODY" else "<status><time>incomplete"
        }
        val result = WorkflowEngine().run(library, session, "RECENT_U2", gateway)
        assertEquals(RunStatus.PAUSED, result.status)
        assertEquals(xml("PREVIOUS_STATE"), result.state[stateKey])
        assertFalse(result.results.containsKey("${prefix}_display"))
        assertFalse(result.results.containsKey("${prefix}_saved"))
        assertEquals("CURRENT_MAIN_BODY", result.replies.single().text)
    }
}
