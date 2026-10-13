package dev.gray.companion.agent

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class AgentFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun writeEditMoveAndDeleteRequireCurrentHash() = runBlocking {
        val root = temporary.newFolder("files")
        val files = AgentFiles(root)
        val created = files.execute("file.write", args("""{"path":"notes/a.md","text":"alpha","expectedHash":null}"""))
        assertEquals("OK", created.status())
        val hash = created.data().getValue("hash").jsonPrimitive.content
        assertEquals("STALE", files.execute("file.write", args("""{"path":"notes/a.md","text":"wrong","expectedHash":null}""")).status())
        val edited = files.execute("file.edit", args("""{"path":"notes/a.md","expectedHash":"$hash","oldText":"alpha","newText":"beta"}"""))
        assertEquals("OK", edited.status())
        val nextHash = edited.data().getValue("hash").jsonPrimitive.content
        assertEquals("STALE", files.execute("file.delete", args("""{"path":"notes/a.md","expectedHash":"$hash"}""")).status())
        assertEquals("OK", files.execute("file.move", args("""{"from":"notes/a.md","to":"notes/b.md","expectedHash":"$nextHash"}""")).status())
        assertEquals("OK", files.execute("file.delete", args("""{"path":"notes/b.md","expectedHash":"$nextHash"}""")).status())
        assertFalse(Files.exists(root.toPath().resolve("notes/b.md")))
    }

    @Test fun traversalAndAmbiguousEditsNeverModifyFiles() = runBlocking {
        val root = temporary.newFolder("files")
        val files = AgentFiles(root)
        assertEquals("INVALID_PATH", files.execute("file.write", args("""{"path":"../outside.txt","text":"x","expectedHash":null}""")).status())
        val created = files.execute("file.write", args("""{"path":"repeat.txt","text":"a a","expectedHash":null}"""))
        val hash = created.data().getValue("hash").jsonPrimitive.content
        assertEquals("AMBIGUOUS", files.execute("file.edit", args("""{"path":"repeat.txt","expectedHash":"$hash","oldText":"a","newText":"b"}""")).status())
        assertEquals("a a", files.execute("file.read", args("""{"path":"repeat.txt"}""")).data().getValue("text").jsonPrimitive.content)
        assertTrue(Files.exists(root.toPath().resolve("repeat.txt")))
    }

    private fun args(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject
    private fun JsonObject.status(): String = getValue("status").jsonPrimitive.content
    private fun JsonObject.data(): JsonObject = getValue("data").jsonObject
}
