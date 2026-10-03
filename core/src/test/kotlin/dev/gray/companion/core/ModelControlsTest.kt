package dev.gray.companion.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelControlsTest {
    @Test fun oldModelsDecodeAndConnectionOwnsResolvedHeaders() {
        val json = Json { encodeDefaults = true }
        val old = json.decodeFromString(ModelProfile.serializer(), """{"id":"model","model":"test"}""")
        assertEquals(ModelReasoning.OFF, old.reasoning)
        assertTrue(old.customBody.isEmpty())
        val connection = ApiConnection(id = "api", customHeaders = listOf(RequestHeader("X-Route", "world")))
        val profile = old.copy(apiConnectionId = connection.id, topP = 0.8f,
            reasoning = ModelReasoning.HIGH,
            customBody = listOf(RequestBodyField("seed", JsonPrimitive(42))))
        val library = Library(connections = listOf(connection), profiles = listOf(profile), selectedProfileId = profile.id)
        val resolved = ModelCatalog.resolveProfile(library)!!
        assertEquals(connection.customHeaders, resolved.resolvedHeaders)
        assertEquals(profile.topP, resolved.topP)
        assertEquals(profile.customBody, resolved.customBody)
        val encoded = json.encodeToString(ModelProfile.serializer(), resolved)
        assertFalse(encoded.contains("resolvedHeaders"))
        assertEquals(profile, json.decodeFromString(ModelProfile.serializer(), encoded))
        assertTrue(LibraryValidator.validate(library).none { it.error })
    }
}
