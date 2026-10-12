package dev.gray.companion.core

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.*
import org.junit.Test

class ConfigurationWorkspaceTest {
    private fun base() = LibraryFormat.upgrade(Library(packages = listOf(
        PromptPackage(id = "pack", name = "Tone", namespace = "tone", slug = "tone", entries = listOf(
            PromptEntry(id = "entry", key = "tone", title = "Tone", text = "before"))))))
    private val field = ExtensionFieldRef(ExtensionResourceKind.PACKAGE, "pack", ExtensionField.ENTRY_TEXT, entryId = "entry")

    @Test fun authoringSchemaDiscoversActualNestedMacroContractAndRejectsUnknownTypes() {
        val directory = ConfigurationAuthoringSchema.describe()
        assertTrue(directory["roots"] is JsonArray)
        assertFalse(directory.containsKey("types"))
        val types = ConfigurationAuthoringSchema.describe("WorkflowNode")["types"] as JsonObject
        val node = types["WorkflowNode"] as JsonObject
        val fields = node["fields"] as JsonArray
        val macro = fields.map { it as JsonObject }.single { it["name"] == JsonPrimitive("userMacro") }
        assertEquals(JsonPrimitive(true), macro["optional"])
        val type = macro["type"] as JsonObject
        assertEquals(JsonPrimitive("UserMacroDefinition"), type["type"])
        assertEquals(JsonPrimitive(true), type["nullable"])
        assertTrue(types.containsKey("UserMacroDefinition"))
        assertFalse(ConfigurationAuthoringSchema.describe("UserMacroDefinition").containsKey("error"))
        for (name in listOf("NoSuchType", "Library", "Session", "ApiConnection", "ModelProfile"))
            assertEquals(JsonPrimitive("UNKNOWN_TYPE"), (ConfigurationAuthoringSchema.describe(name)["error"] as JsonObject)["code"])
    }

    private fun stage(workspace: ConfigurationWorkspace, text: String = "after"): ConfigurationWorkspace {
        val result = ConfigurationWorkspaces.stageField(workspace, workspace.revision, field,
            ExtensionFieldRegistry.read(workspace.draft, field), text)
        assertEquals(result.issues.toString(), ConfigurationWorkspaceResultStatus.OK, result.status)
        return result.workspace
    }

    @Test fun isolatedDraftsCommitOnlyReviewedRevisionAndKeepUnrelatedLiveChanges() {
        val live = base()
        val first = ConfigurationWorkspaces.open(live)
        val other = ConfigurationWorkspaces.open(live)
        val staged = stage(first)
        assertEquals("before", live.packages.single().entries.single().text)
        assertEquals(live, other.draft)
        assertEquals(ConfigurationWorkspaceResultStatus.STALE, ConfigurationWorkspaces.commitPlan(staged, staged.revision).status)
        val reviewed = ConfigurationWorkspaces.preview(staged, staged.revision).workspace
        val plan = requireNotNull(ConfigurationWorkspaces.commitPlan(reviewed, reviewed.revision).plan)
        val newer = stage(reviewed, "newer")
        assertNull(newer.fixedPreview)
        assertEquals(ConfigurationWorkspaceResultStatus.STALE, ConfigurationWorkspaces.commitPlan(newer, reviewed.revision).status)
        val frozen = CompletedWorld(live, CapturedSessionChoices("", ""), "", ExecutionSemantics.CURRENT)
        val progressed = live.copy(sessions = listOf(Session(id = "chat", executableWorld = frozen,
            messages = listOf(ChatMessage(text = "hello")))))
        val applied = ConfigurationCommands.apply(plan, progressed)
        assertNotNull(applied.issues.toString(), applied.library)
        assertEquals(progressed.sessions, applied.library!!.sessions)
        assertEquals("after", applied.library!!.packages.single().entries.single().text)
        val undone = ConfigurationCommands.undo(requireNotNull(applied.receipt), applied.library!!)
        assertEquals(progressed, undone.library)
        val independentlyEdited = live.copy(packages = listOf(live.packages.single().copy(name = "Changed elsewhere")))
        assertNull(ConfigurationCommands.apply(plan, independentlyEdited).library)
        val closed = ConfigurationWorkspaces.committed(reviewed, applied.receipt!!.id)
        assertEquals(ConfigurationWorkspaceResultStatus.CLOSED, ConfigurationWorkspaces.stageCommands(closed, closed.revision, "{}").status)
    }

    @Test fun invalidBatchAndPrivilegedNoOpsDoNotAdvanceOrPartiallyChangeDraft() {
        val workspace = ConfigurationWorkspaces.open(base())
        val invalidBatch = """{"format":"companion.config.v1","commands":[
          {"op":"mergePatch","kind":"package","id":"pack","patch":{"name":"Changed"}},
          {"op":"mergePatch","kind":"package","id":"missing","patch":{"name":"Broken"}}
        ]}"""
        val failure = ConfigurationWorkspaces.stageCommands(workspace, 0, invalidBatch)
        assertEquals(ConfigurationWorkspaceResultStatus.INVALID, failure.status)
        assertSame(workspace, failure.workspace)
        for (command in listOf(
            """{"op":"select","key":"selectedProfileId","value":null}""",
            """{"op":"put","kind":"profile","value":{"id":"profile"}}"""
        )) {
            val result = ConfigurationWorkspaces.stageCommands(workspace, 0,
                """{"format":"companion.config.v1","commands":[$command]}""")
            assertEquals(ConfigurationWorkspaceResultStatus.INVALID, result.status)
            assertSame(workspace, result.workspace)
        }
        val staleField = ConfigurationWorkspaces.stageField(workspace, 0, field, "wrong", "replacement")
        assertSame(workspace, staleField.workspace)
        assertEquals(ConfigurationWorkspaceResultStatus.INVALID, staleField.status)
    }

    @Test fun advertisedExampleAndCumulativeFieldExportUseRealConfigurationCommands() {
        val workspace = ConfigurationWorkspaces.open(base())
        val sample = ConfigurationWorkspaces.stageCommands(workspace, 0, ConfigurationContext.manifest().commandExample)
        assertEquals(sample.issues.toString(), ConfigurationWorkspaceResultStatus.OK, sample.status)
        val changed = stage(sample.workspace)
        val exported = requireNotNull(ConfigurationWorkspaces.exportCommands(changed))
        val restored = ConfigurationWorkspaces.stageCommands(workspace, 0, exported)
        assertEquals(restored.issues.toString(), ConfigurationWorkspaceResultStatus.OK, restored.status)
        assertEquals(changed.draft, restored.workspace.draft)
        assertEquals(ConfigurationCommands.FORMAT, ConfigurationContext.manifest().format)
    }

    @Test fun contextExcludesCredentialsReasoningAndReadsFrozenDefinitionsExplicitly() {
        val original = base()
        val frozen = CompletedWorld(original, CapturedSessionChoices("", ""), "", ExecutionSemantics.CURRENT)
        val session = Session(id = "chat", executableWorld = frozen, messages = listOf(
            ChatMessage(id = "hidden", text = "hidden non-body", outputSlot = OutputSlot.BEFORE_BODY),
            ChatMessage(id = "deleted", text = "deleted", deleted = true),
            ChatMessage(id = "body", role = Role.ASSISTANT, text = "body text", rawText = "private raw",
                reasoningSnapshot = ReasoningSnapshot(text = "private reasoning"))))
        val live = original.copy(packages = listOf(original.packages.single().copy(name = "Live")),
            sessions = listOf(session), selectedSessionId = session.id,
            connections = listOf(ApiConnection(id = "connection", nativeProvider = buildJsonObject { put("secret", "private secret") })),
            profiles = listOf(ModelProfile(id = "profile", name = "Model", model = "example", nativeModel = buildJsonObject { put("secret", "private secret") })))
        assertNull(ConfigurationContext.readResource(live, "connection", "connection"))
        assertNull(ConfigurationContext.readResource(live, "profile", "profile"))
        assertTrue(ConfigurationContext.listResources(live).none { it.kind in setOf("connection", "profile") })
        assertEquals(listOf(ConfigurationModelSummary("profile", "Model", "example")), ConfigurationContext.models(live))
        val context = requireNotNull(ConfigurationContext.sessionContext(live, "chat", maxChars = 4))
        assertEquals("session.executableWorld", context.resourceWorld)
        assertEquals(listOf("body"), context.messages.map { it.id })
        assertEquals("body", context.messages.single().text)
        assertTrue(context.truncated)
        assertFalse(LibraryFormat.json.encodeToString(ConfigurationSessionContext.serializer(), context).contains("private"))
        assertEquals(JsonPrimitive("Tone"), ConfigurationContext.readSessionResource(live, "chat", "package", "pack")!!["name"])
        assertEquals(JsonPrimitive("Live"), ConfigurationContext.readResource(live, "package", "pack")!!["name"])
    }
}
