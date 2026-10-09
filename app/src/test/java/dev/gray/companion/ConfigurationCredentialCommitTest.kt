package dev.gray.companion

import dev.gray.companion.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ConfigurationCredentialCommitTest {
    private class Secrets(initial: Map<String, String>) : CredentialStore {
        val values = initial.toMutableMap()
        var failNextId: String? = null
        var puts = 0
        override fun get(profileId: String) = values[profileId].orEmpty()
        override fun put(profileId: String, secret: String) {
            puts++
            values[profileId] = secret
            if (failNextId == profileId) { failNextId = null; error("simulated partial write failure") }
        }
        override fun remove(profileId: String) { values.remove(profileId) }
    }

    private class Repository(var value: Library) : LibraryRepository {
        var saves = 0
        var fail = false
        var onSave: (() -> Unit)? = null
        override suspend fun load() = value
        override suspend fun save(library: Library) {
            saves++
            onSave?.invoke()
            if (fail) error("simulated transaction failure")
            value = library
        }
    }

    @Test fun explicitKeysAreReadyForOneSaveAndNeverSerializedWithResources() = runBlocking {
        val repository = Repository(Library())
        val secrets = Secrets(mapOf("existing" to "old test key", "untouched" to "other test key"))
        val next = Library(connections = listOf(ApiConnection(id = "existing")))
        repository.onSave = { assertEquals("new test key", secrets.get("existing")) }
        assertTrue(ConfigurationCredentialCommit.save(repository, secrets, next,
            listOf(CredentialWrite("existing", "old test key", "new test key"))).isEmpty())
        assertEquals(next, repository.value)
        assertEquals(1, repository.saves)
        assertEquals("other test key", secrets.get("untouched"))
        assertFalse(LibraryFormat.encode(next).contains("new test key"))
    }

    @Test fun partialKeyWriteOrLibraryFailureRestoresOldKeysAndRemovesNewKey() = runBlocking {
        for (failDuringKey in listOf(true, false)) {
            val base = Library(connections = listOf(ApiConnection(id = "existing")))
            val next = base.copy(connections = base.connections + ApiConnection(id = "new"))
            val repository = Repository(base).apply { fail = !failDuringKey }
            val original = mapOf("existing" to "old test key", "untouched" to "other test key")
            val secrets = Secrets(original).apply { if (failDuringKey) failNextId = "new" }
            val issues = ConfigurationCredentialCommit.save(repository, secrets, next, listOf(
                CredentialWrite("existing", "old test key", "changed test key"),
                CredentialWrite("new", "", "new test key")))
            assertTrue(issues.any { it.error })
            assertEquals(base, repository.value)
            assertEquals(original, secrets.values)
            assertEquals(if (failDuringKey) 0 else 1, repository.saves)
        }
    }

    @Test fun staleKeyRefusesTheSaveBeforeAnyWrite() = runBlocking {
        val next = Library(connections = listOf(ApiConnection(id = "existing")))
        val repository = Repository(next)
        val secrets = Secrets(mapOf("existing" to "already changed test key"))
        val issues = ConfigurationCredentialCommit.save(repository, secrets, next,
            listOf(CredentialWrite("existing", "older test key", "desired test key")))
        assertTrue(issues.any { it.error && it.path == "connection.existing.apiKey" })
        assertEquals(0, secrets.puts)
        assertEquals(0, repository.saves)
        assertEquals("already changed test key", secrets.get("existing"))
    }
}
