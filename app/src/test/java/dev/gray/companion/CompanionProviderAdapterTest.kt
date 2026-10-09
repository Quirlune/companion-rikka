package dev.gray.companion

import dev.gray.companion.core.*
import kotlinx.serialization.json.JsonPrimitive
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.ProviderSetting
import org.junit.Assert.*
import org.junit.Test
import kotlin.uuid.Uuid

class CompanionProviderAdapterTest {
    private fun library(): Library = LibraryFormat.upgrade(Library(
        connections = listOf(
            ApiConnection(id = "legacy-api:模型/old", name = "Primary", baseUrl = "https://example.invalid/v1",
                responseApi = true, customHeaders = listOf(RequestHeader("x-client", "desktop")), maxConcurrentRequests = 1),
            ApiConnection(id = "other-connection", name = "Other", protocol = Protocol.GOOGLE, baseUrl = "https://example.invalid/v1beta")),
        profiles = listOf(
            ModelProfile(id = "legacy/profile:一", name = "Careful", model = "same-model", apiConnectionId = "legacy-api:模型/old",
                protocol = Protocol.CLAUDE, baseUrl = "https://fallback.invalid", temperature = .3f, topP = .8f, maxTokens = 1234,
                reasoning = ModelReasoning.HIGH, customBody = listOf(RequestBodyField("seed", JsonPrimitive(42)))),
            ModelProfile(id = "p-other", name = "Other model", model = "other", apiConnectionId = "other-connection"),
            ModelProfile(id = "second/same-model", name = "Fast", model = "same-model", apiConnectionId = "legacy-api:模型/old")),
        selectedProfileId = "legacy/profile:一",
    ))

    @Test fun opaqueIdsRoundTripPreservesRequestValuesAndIndependentOrders() {
        val base = library()
        val snapshot = CompanionProviderAdapter.capture(base)
        assertTrue(snapshot.providers.all { CompanionProviderAdapter.apiKey(it).isEmpty() })
        assertEquals(snapshot.connectionIds, CompanionProviderAdapter.capture(base).connectionIds)
        assertEquals(snapshot.profileIds, CompanionProviderAdapter.capture(base).profileIds)
        val provider = snapshot.providers.first() as ProviderSetting.OpenAI
        assertEquals(2, provider.models.map { it.id }.distinct().size)
        val identity = CompanionProviderAdapter.plan(snapshot, snapshot.providers).plan!!
        assertEquals(base, CompanionProviderAdapter.apply(identity, base).library)
        val edited = provider.copy(name = "Renamed API", enabled = false, models = provider.models.mapIndexed { index, model ->
            if (index == 0) model.copy(displayName = "Renamed profile", modelId = "new-model-id") else model
        })
        val editPlan = CompanionProviderAdapter.plan(snapshot, listOf(snapshot.providers[1], edited)).plan!!
        val result = CompanionProviderAdapter.apply(editPlan, base).library!!
        assertEquals(base.connections.reversed().map { it.id }, result.connections.map { it.id })
        assertEquals(base.profiles.map { it.id }, result.profiles.map { it.id })
        assertEquals(base.profiles.first().copy(name = "Renamed profile", model = "new-model-id"), result.profiles.first())
        assertEquals(base.connections.first().copy(name = "Renamed API", enabled = false), result.connections.last())
        assertTrue(editPlan.credentialChanges.isEmpty())

        val localReorder = CompanionProviderAdapter.plan(snapshot, listOf(provider.copy(models = provider.models.reversed()), snapshot.providers[1])).plan!!
        assertEquals(listOf("second/same-model", "p-other", "legacy/profile:一"), localReorder.profiles.map { it.id })
    }

    @Test fun firstCreatedModelIsDefaultAndCredentialsRemainAnExplicitSeparateDiff() {
        val base = LibraryFormat.upgrade(Library())
        val snapshot = CompanionProviderAdapter.capture(base)
        val provider = ProviderSetting.OpenAI(id = Uuid.parse("10000000-0000-0000-0000-000000000001"), name = "New",
            apiKey = "fixture-not-a-real-key", models = listOf(Model(id = Uuid.parse("20000000-0000-0000-0000-000000000001"), modelId = "chat", displayName = "Chat")))
        val noExplicitKey = CompanionProviderAdapter.plan(snapshot, listOf(provider)).plan!!
        assertTrue(noExplicitKey.credentialChanges.isEmpty())
        val plan = CompanionProviderAdapter.plan(snapshot, listOf(provider), editedApiKeys = mapOf(provider.id to ("" to provider.apiKey))).plan!!
        val result = CompanionProviderAdapter.apply(plan, base).library!!
        assertEquals(result.profiles.single().id, result.selectedProfileId)
        assertEquals(result.connections.single().id, result.profiles.single().apiConnectionId)
        assertEquals(result.connections.single().id, plan.credentialChanges.single().connectionId)
        assertEquals("", plan.credentialChanges.single().before)
        assertEquals(provider.apiKey, plan.credentialChanges.single().after)
        assertFalse(LibraryFormat.encode(result).contains(provider.apiKey))
        assertTrue(CompanionProviderAdapter.capture(result).providers.all { CompanionProviderAdapter.apiKey(it).isEmpty() })
        val intentionalNoDefault = result.copy(selectedProfileId = null)
        val unchanged = CompanionProviderAdapter.capture(intentionalNoDefault)
        assertNull(CompanionProviderAdapter.apply(CompanionProviderAdapter.plan(unchanged, unchanged.providers).plan!!, intentionalNoDefault).library!!.selectedProfileId)
    }

    @Test fun staleRequestsConflictAndControllerValidationRejectsReferencedModelDeletion() {
        val initial = library()
        val workflow = Workflow(id = "workflow", modelProfileId = initial.profiles.first().id,
            nodes = listOf(WorkflowNode(id = "node", blocks = emptyList(), generation = ModelCallOptions(profileId = initial.profiles.last().id))))
        val base = LibraryFormat.upgrade(initial.copy(mainCalls = listOf(MainCall(id = "main", name = "Main", workflows = listOf(workflow)))))
        val baselineIssues = LibraryValidator.validate(base)
        assertTrue(baselineIssues.toString(), baselineIssues.none { it.error })
        val snapshot = CompanionProviderAdapter.capture(base)
        val provider = snapshot.providers.first()
        val plan = CompanionProviderAdapter.plan(snapshot, snapshot.providers.map { if (it.id == provider.id) it.copyProvider(models = emptyList()) else it }).plan!!
        val candidate = CompanionProviderAdapter.apply(plan, base).library!!
        assertNull(candidate.selectedProfileId)
        // Adapter only prepares. This is the same full admission validator used by Controller before publication.
        val deletionIssues = LibraryValidator.validate(candidate)
        assertTrue(deletionIssues.any { it.error && it.path.endsWith("modelProfileId") })
        assertTrue(deletionIssues.any { it.error && it.path.endsWith("generation.profileId") })
        val laterRequestEdit = base.copy(profiles = base.profiles.mapIndexed { index, profile -> if (index == 0) profile.copy(topP = .5f) else profile })
        assertNull(CompanionProviderAdapter.apply(plan, laterRequestEdit).library)
        val laterConnectionEdit = base.copy(connections = base.connections.mapIndexed { index, connection -> if (index == 0) connection.copy(maxConcurrentRequests = 2) else connection })
        assertNull(CompanionProviderAdapter.apply(plan, laterConnectionEdit).library)
        val unrelated = base.copy(user = base.user.copy(name = "Later user"))
        assertEquals(unrelated.user, CompanionProviderAdapter.apply(plan, unrelated).library!!.user)
        val moved = snapshot.providers.mapIndexed { index, item -> when (index) {
            0 -> item.copyProvider(models = emptyList())
            else -> item.copyProvider(models = item.models + provider.models)
        } }
        assertNull(CompanionProviderAdapter.plan(snapshot, moved).plan)
    }
}
