package dev.gray.companion.core

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.json.Json

class ModelCatalogTest {
    @Test fun legacyMigrationPreservesIdsAndIsIdempotentWithoutCredentials() {
        val profile = ModelProfile(id = "old", name = "Old", protocol = Protocol.CLAUDE, baseUrl = "https://example.com", model = "model")
        val original = Library(profiles = listOf(profile), selectedProfileId = profile.id)
        val normalized = ModelCatalog.normalize(original)
        assertEquals(profile.id, normalized.profiles.single().id)
        assertEquals(profile.baseUrl, normalized.connections.single().baseUrl)
        assertEquals(profile.protocol, normalized.connections.single().protocol)
        assertEquals(normalized, ModelCatalog.normalize(normalized))
        assertEquals(profile.model, ModelCatalog.resolveProfile(normalized)?.model)
        assertFalse(Json.encodeToString(Library.serializer(), normalized).contains("apiKey"))
    }

    @Test fun sharedConnectionOverridesLegacyTransportAndCanBeDisabled() {
        val connection = ApiConnection(id = "api", protocol = Protocol.GOOGLE, baseUrl = "https://example.com")
        val models = listOf("one", "two").map { ModelProfile(id = it, model = it, apiConnectionId = connection.id) }
        val library = Library(profiles = models, connections = listOf(connection))
        models.forEach { profile ->
            val effective = ModelCatalog.resolveProfile(library, profile.id)!!
            assertEquals(connection.protocol, effective.protocol)
            assertEquals(connection.baseUrl, effective.baseUrl)
            assertEquals(connection.id, ModelCatalog.credentialId(effective))
        }
        assertNull(ModelCatalog.resolveProfile(library.copy(connections = listOf(connection.copy(enabled = false))), models.first().id))
    }
}
