package dev.gray.companion.core

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Test

class PluginParticipationTest {
    private val groupRef = ExtensionResourceRef(ExtensionResourceKind.GROUP, "feature")
    private val code = PluginCode(source = "function execute(context) { return context.input; }")
    private fun base(): Library {
        val session = Session(id = "chat", mainCallId = "main", enabledExtensionIds = listOf("one", "two"))
        // Explicitly exercise the legacy nullable switch contract without live migration.
        return Library(mainCalls = listOf(MainCall(id = "main", name = "Main", workflows = emptyList())),
            groups = listOf(CallGroup(id = "feature", name = "Feature", boundPackageIds = listOf("pack"))),
            packages = listOf(PromptPackage(id = "pack", namespace = "lore", name = "Lore")),
            extensions = listOf(ConfigurationExtension(id = "one", name = "One", resources = listOf(groupRef), code = code),
                ConfigurationExtension(id = "two", name = "Two", resources = listOf(groupRef))),
            sessions = listOf(session), selectedSessionId = session.id)
    }

    @Test fun participationDoesNotEditMainAndDisablingPreservesOtherOwnersAndAcceptedBody() {
        val original = base()
        val session = original.sessions.single()
        val origin = ResultOrigin("feature", "flow", "node")
        val tagged = PromptInjection("owned", PromptBlock(template = "plugin text"), origin,
            scope = InjectionScope.SESSION, pluginOwnerIds = listOf("one", "two"))
        val oneOnly = tagged.copy(id = "one-only", pluginOwnerIds = listOf("one"))
        val manual = tagged.copy(id = "manual", pluginOwnerIds = emptyList())
        val body = ChatMessage(role = Role.ASSISTANT, text = "accepted frontend body")
        val library = original.copy(sessions = listOf(session.copy(promptInjections = listOf(tagged, oneOnly, manual),
            state = mapOf("shared" to "retained"), messages = listOf(body))))
        assertEquals(listOf("one", "two"), PluginParticipation.injectionOwners(library, library.sessions.single(), "feature"))
        val disabled = ExtensionCommands.setParticipation(library, library.extensions.first(), "chat", false, true)
        assertTrue(disabled.issues.toString(), disabled.issues.none { it.error })
        val next = requireNotNull(disabled.library)
        assertEquals(library.mainCalls, next.mainCalls)
        assertEquals(library.groups, next.groups)
        assertTrue(next.sessions.single().groupIds.isEmpty())
        assertEquals(listOf("feature"), next.loadedCallGroups(next.sessions.single()).map { it.id })
        assertEquals(listOf("pack"), next.loadedPromptPackages(next.sessions.single()).map { it.id })
        assertEquals(listOf("owned", "manual"), next.sessions.single().promptInjections.map { it.id })
        assertEquals(listOf("two"), next.sessions.single().promptInjections.first().pluginOwnerIds)
        assertEquals(listOf(body), next.sessions.single().messages)
        assertEquals(mapOf("shared" to "retained"), next.sessions.single().state)
        assertNull(ExtensionCommands.setParticipation(next, next.extensions.first(), "chat", true, true).library)
        val manualSession = session.copy(groupIds = listOf("feature"))
        assertTrue(PluginParticipation.injectionOwners(original, manualSession, "feature").isEmpty())
        val inactive = manualSession.copy(enabledExtensionIds = emptyList())
        assertEquals(listOf("feature"), original.loadedCallGroups(inactive).map { it.id })
        assertFalse(PluginParticipation.injectionActive(original, inactive, listOf("one"), "feature"))
    }

    @Test fun disabledCodeResultIsHiddenEvenWhenItsGroupRemainsManuallyLoaded() {
        val original = base()
        val node = WorkflowNode(id = "node", actionId = "plugin.run", includeHistory = false,
            outputKey = "code", arguments = mapOf("pluginId" to "one"))
        val library = original.copy(groups = listOf(original.groups.single().copy(workflows = listOf(Workflow(id = "flow", nodes = listOf(node))))))
        val origin = ResultOrigin("feature", "flow", "node")
        val session = original.sessions.single().copy(groupIds = listOf("feature"), enabledExtensionIds = emptyList(),
            results = mapOf("code" to "retained accepted output"), resultOrigins = mapOf("code" to origin),
            macroHistory = mapOf("result.code" to listOf(MacroVersion(1, "retained accepted output", origin))))
        assertFalse(PluginParticipation.nodeActive(library, session, node))
        assertNull(MacroReferences.currentVersion(library, session, "result.code"))
        assertNull(MacroReferences.baseValue(library, session, "result.code"))
        val active = session.copy(enabledExtensionIds = listOf("one"))
        assertEquals("retained accepted output", MacroReferences.currentVersion(library, active, "result.code")?.text)
        assertEquals("retained accepted output", session.results["code"])
    }

    @Test fun zipCodeIsPortableConfigurationAndIsNeverExecutedByImport() {
        val archive = ByteArrayOutputStream()
        ZipOutputStream(archive).use { zip ->
            fun member(path: String, text: String) {
                zip.putNextEntry(ZipEntry(path)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            member("manifest.json", """{"format":"companion.bundle.v1","files":[{"path":"extension.json","kind":"extension"},{"path":"execute.js","kind":"text"}]}""")
            member("extension.json", """{"id":"code-plugin","name":"Code plugin","code":{"runtime":"javascript","version":1,"source":{"${'$'}text":"execute.js"}}}""")
            member("execute.js", code.source)
        }
        val directory = Files.createTempDirectory("plugin-import").toFile()
        try {
            val imported = ResourceArchiveImport.read(ByteArrayInputStream(archive.toByteArray()), directory, "plugin.zip")
            assertTrue(imported.issues.toString(), imported.issues.none { it.error })
            val preview = ConfigurationCommands.preview(requireNotNull(imported.document), Library())
            assertTrue(preview.issues.toString(), preview.issues.none { it.error })
            val result = ConfigurationCommands.apply(requireNotNull(preview.plan), Library())
            val library = requireNotNull(result.library)
            assertEquals(code, library.extensions.single().code)
            assertTrue(library.sessions.isEmpty())
            assertEquals(library, LibraryFormat.decode(LibraryFormat.encode(library)))
            assertTrue(PluginCodes.validate(code.copy(source = "中".repeat(PluginCodes.MAX_SOURCE_BYTES / 2))).any { it.error })
            assertTrue(PluginCodes.validate(code.copy(runtime = "unknown")).any { it.error })
        } finally { directory.delete() }
    }
}
