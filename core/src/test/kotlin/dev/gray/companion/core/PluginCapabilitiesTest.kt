package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class PluginCapabilitiesTest {
    @Test fun declarationsRequireCanonicalOriginsMethodsAndAliases() {
        val valid = PluginPermission("network.http", listOf("https://api.example.com:8443"),
            listOf("GET", "POST"), listOf("serviceKey"))
        assertTrue(PluginCapabilities.validate(listOf(valid)).toString(), PluginCapabilities.validate(listOf(valid)).isEmpty())
        for (origin in listOf("http://api.example.com", "https://API.example.com", "https://api.example.com/",
            "https://user@api.example.com", "https://api.example.com/path", "https://api.example.com?q=x",
            "https://*.example.com", "https://api.example.com#part")) {
            assertNotNull(origin, PluginCapabilities.originIssue(origin))
        }
        assertNull(PluginCapabilities.originIssue("https://api.example.com"))
        val invalid = listOf(
            PluginPermission("runtime.sleep"),
            PluginPermission("network.http", listOf("https://api.example.com"), listOf("get"), listOf("1bad")),
            PluginPermission("network.http")
        )
        assertTrue(PluginCapabilities.validate(invalid).size >= 4)
        assertTrue(PluginCodes.validate(PluginCode(source = "return 'x'", permissions = listOf(valid))).any { it.error })
    }

    @Test fun oldPluginShapeDecodesAndFingerprintCoversCodeAndPermissions() {
        val old = LibraryFormat.json.decodeFromString(PluginCode.serializer(),
            """{"runtime":"javascript","version":1,"source":"function execute(context) { return context.input; }"}""")
        assertTrue(old.permissions.isEmpty())
        assertTrue(PluginCodes.validate(old).isEmpty())
        val v2 = old.copy(version = 2, permissions = listOf(PluginPermission("network.http", listOf("https://api.example.com"))))
        assertTrue(PluginCodes.validate(v2).toString(), PluginCodes.validate(v2).isEmpty())
        assertEquals(64, PluginCapabilities.fingerprint(v2).length)
        assertNotEquals(PluginCapabilities.fingerprint(v2), PluginCapabilities.fingerprint(v2.copy(source = "changed")))
        assertNotEquals(PluginCapabilities.fingerprint(v2), PluginCapabilities.fingerprint(v2.copy(permissions = emptyList())))
        assertNotNull(PluginCapabilities.manifest()["capabilities"])
    }

    @Test fun v2AcceptedResultSurvivesForkWithoutReexecutingAndOldExecutionRejectsIt() = runBlocking {
        var calls = 0
        val executor = PluginCodeExecutor { calls++; "accepted external result" }
        val node = WorkflowNode(id = "plugin-node", actionId = "plugin.run", outputKey = "plugin-result",
            arguments = mapOf("pluginId" to "plugin"), includeHistory = false, recordTrajectory = false)
        val group = CallGroup(id = "plugin-group", name = "Plugin calls",
            workflows = listOf(Workflow(id = "plugin-flow", nodes = listOf(node))))
        val plugin = ConfigurationExtension(id = "plugin", name = "Plugin",
            resources = listOf(ExtensionResourceRef(ExtensionResourceKind.GROUP, group.id)),
            code = PluginCode(version = 2, source = "async function execute(context, host) { return 'accepted'; }"))
        val start = Session(id = "session", characterId = "card", mainCallId = "main",
            enabledExtensionIds = listOf(plugin.id), messages = listOf(ChatMessage(id = "turn", role = Role.USER, text = "question")))
        val entryNode = WorkflowNode(id = "entry", actionId = "text.render",
            inputTemplate = "entry", outputKey = "entry", includeHistory = false)
        val main = MainCall(id = "main", name = "Main", workflows = listOf(Workflow(id = "entry-flow", nodes = listOf(entryNode))))
        val library = Library(characters = listOf(CharacterCard(id = "card", namespace = "hero", name = "Hero")),
            mainCalls = listOf(main),
            profiles = listOf(ModelProfile(id = "model", model = "offline")), selectedProfileId = "model",
            groups = listOf(group), extensions = listOf(plugin), sessions = listOf(start), selectedSessionId = start.id)
        val oldContext = ActionContext(node, "question", start.messages, node.arguments, emptyMap(), emptyMap(),
            null, object : ModelGateway {
                override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>, onDelta: suspend (String) -> Unit): String = error("offline")
            }, {}, session = start, library = library, executionVersion = 26)
        assertTrue(runCatching { PluginCodeActions.prepare(oldContext) }.isFailure)
        val gateway = oldContext.gateway
        val result = WorkflowEngine(ActionRegistry.defaults(executor)).run(library, start, "question", gateway)
        assertEquals(result.traces.toString(), RunStatus.SUCCESS, result.status)
        assertEquals(1, calls)
        assertEquals("accepted external result", result.results["plugin-result"])
        assertTrue(result.completedWorld!!.recipes.none { it.kind == PureReplayKind.PLUGIN_TEXT })
        val committed = MessageOperations.recordSuccessfulTurn(start, result).session!!
        val variant = committed.turnRecords.single().variants.single()
        assertEquals("accepted external result", variant.after.results["plugin-result"])
        val fork = MessageOperations.forkAtVariant(committed, "turn", variant.id).session!!
        val replay = BranchReplay.rebuild(fork, executor)
        assertTrue(replay.issues.toString(), replay.succeeded)
        assertEquals("accepted external result", replay.session!!.results["plugin-result"])
        assertEquals(1, calls)
    }
}
