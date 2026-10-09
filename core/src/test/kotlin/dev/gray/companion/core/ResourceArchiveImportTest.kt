package dev.gray.companion.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.CancellationException
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ResourceArchiveImportTest {
    private fun zip(vararg files: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { archive -> files.forEach { (name, data) ->
            archive.putNextEntry(ZipEntry(name)); archive.write(data); archive.closeEntry()
        } }
    }.toByteArray()
    private fun utf8(text: String) = text.toByteArray(Charsets.UTF_8)
    private fun <T> staging(block: (File) -> T): T {
        val directory = Files.createTempDirectory("companion-archive-test").toFile()
        val unrelated = File(directory, "keep.txt").apply { writeText("keep") }
        try {
            val result = block(directory)
            assertEquals("keep", unrelated.readText())
            assertEquals(listOf("keep.txt"), directory.listFiles()!!.map { it.name })
            return result
        } finally { directory.deleteRecursively() }
    }
    private fun read(bytes: ByteArray, limits: ResourceArchiveLimits = ResourceArchiveLimits()) = staging {
        ResourceArchiveImport.read(ByteArrayInputStream(bytes), it, limits = limits)
    }
    private fun manifest(files: String) = utf8("""{"format":"companion.bundle.v1","label":"Example","files":[$files]}""")

    @Test fun splitResourcesKeepExactTextAndOneFixedApplyUndoPlan() {
        val text = "\uFEFF  first\r\nsecond\n\n"
        val archive = zip(
            "manifest.json" to manifest("""{"path":"hero.json","kind":"character","id":"hero"},{"path":"world.json","kind":"package"},{"path":"main.json","kind":"mainCall"},{"path":"prompts/world.md","kind":"text"},{"path":"README.md","kind":"documentation"}"""),
            "hero.json" to utf8("""{"name":"Hero","namespace":"hero","boundPackageIds":["world"],"entries":[]}"""),
            "world.json" to utf8("""{"id":"world","name":"World","namespace":"world","entries":[{"key":"setting","text":{"${'$'}text":"prompts/world.md"}}]}"""),
            "main.json" to utf8("""{"id":"main","name":"Main","boundPackageIds":["world"]}"""),
            "prompts/world.md" to utf8(text), "README.md" to utf8("Editable example"))
        val result = read(archive)
        assertTrue(result.issues.toString(), result.issues.none { it.error })
        assertTrue(result.issues.any { it.path == "README.md" && !it.error })
        val base = LibraryFormat.upgrade(Library())
        val preview = ConfigurationCommands.preview(requireNotNull(result.document), base)
        assertTrue(preview.issues.toString(), preview.issues.none { it.error })
        val applied = ConfigurationCommands.apply(requireNotNull(preview.plan), base)
        assertTrue(applied.issues.toString(), applied.issues.none { it.error })
        val imported = requireNotNull(applied.library)
        assertEquals(text, imported.packages.single().entries.single().text)
        val secondApply = ConfigurationCommands.apply(preview.plan!!, base)
        assertEquals(imported, secondApply.library)
        assertEquals(base, ConfigurationCommands.undo(requireNotNull(applied.receipt), imported).library)
        val edited = imported.copy(packages = imported.packages.map { it.copy(name = "later") })
        assertTrue(ConfigurationCommands.undo(applied.receipt!!, edited).issues.any { it.error })
    }

    @Test fun originalJsonAndNativeBackupRemainUnchangedIncludingBom() {
        val source = """{"schemaVersion":12,"characters":[],"sessions":[],"savedChats":[],"selectedSessionId":null}"""
        val direct = read(utf8("\uFEFF$source"))
        val bundled = read(zip("backup.json" to utf8(source), "README.md" to utf8("backup")))
        val expected = LibraryFormat.json.parseToJsonElement(source)
        assertEquals(expected, direct.document)
        assertEquals(expected, bundled.document)
        assertTrue(bundled.issues.toString(), bundled.issues.none { it.error })
        val invalidCombination = read(zip("backup.json" to utf8(source), "characters/extra.json" to utf8("""{"id":"extra","name":"Extra","namespace":"extra"}""")))
        assertNull(invalidCombination.document)
    }

    @Test fun pngV3WinsPreservesForeignSourceAndKeepsUnsupportedGreetingInactive() {
        val old = """{"name":"Old","description":"old","personality":"","scenario":"","first_mes":"","mes_example":""}"""
        val card = """{"spec":"chara_card_v3","spec_version":"3.0","data":{"name":"New","description":"{{char}} meets {{user}}","personality":"quiet","scenario":"town","first_mes":"{{random::hello::hi}}","mes_example":"example","creator_notes":"credit","character_book":{"entries":[{"content":"lore {{char}}","keys":["town"]}]}}}"""
        val result = read(png("chara" to old, "ccv3" to card))
        assertTrue(result.issues.toString(), result.issues.none { it.error })
        val preview = ConfigurationCommands.preview(requireNotNull(result.document), LibraryFormat.upgrade(Library()))
        assertTrue(preview.issues.toString(), preview.issues.none { it.error })
        val library = ConfigurationCommands.apply(requireNotNull(preview.plan), LibraryFormat.upgrade(Library())).library!!
        val imported = library.characters.single()
        assertEquals("New", imported.name)
        assertEquals("", imported.greeting)
        assertEquals("{{character.name}} meets {{user.name}}", imported.entries.first { it.key == "profile" }.text)
        assertNotNull(imported.avatar?.decodedBytes())
        val source = imported.entries.single { it.key == "import_source" }
        assertFalse(source.enabled); assertFalse(source.isTemplate)
        assertEquals(LibraryFormat.json.parseToJsonElement(card), LibraryFormat.json.parseToJsonElement(source.text))
        assertTrue(imported.entries.any { !it.enabled && it.text.contains("{{random::") })
        assertFalse(library.packages.single().entries.single().enabled)
        assertEquals(listOf(library.packages.single().id), imported.boundPackageIds)
    }

    @Test fun rejectsTraversalDuplicateUnknownMissingAndInvalidUtf8WithoutLeavingStage() {
        val failures = listOf(
            zip("../escape.json" to utf8("{}")),
            zip("card.json" to utf8("{}"), "CARD.JSON" to utf8("{}")),
            zip("payload.bin" to byteArrayOf(1, 2, 3)),
            zip("packages/p.json" to utf8("""{"id":"p","name":"P","namespace":"p","entries":[{"key":"a","text":{"${'$'}text":"missing.md"}}]}""")),
            zip("packages/p.json" to utf8("""{"id":"p","name":"P","namespace":"p","entries":[{"key":"a","text":{"${'$'}text":"bad.md"}}]}"""), "bad.md" to byteArrayOf(0xc3.toByte(), 0x28)),
            zip("packages/a.json" to utf8("""{"id":"p"}"""), "packages/b.json" to utf8("""{"id":"p"}""")),
            png().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() })
        failures.forEach { bytes -> val result = read(bytes); assertNull(result.document); assertTrue(result.issues.any { it.error }) }
    }

    @Test fun realExpandedBytesAndRepeatedMaterializationAreBounded() {
        val source = """{"id":"p","name":"P","namespace":"p","entries":[{"key":"a","text":{"${'$'}text":"x.md"}},{"key":"b","text":{"${'$'}text":"x.md"}}]}"""
        val archive = zip("packages/p.json" to utf8(source), "x.md" to utf8("x".repeat(300)))
        assertNull(read(archive, ResourceArchiveLimits(maxExpandedBytes = 200)).document)
        assertNull(read(archive, ResourceArchiveLimits(maxMaterializedBytes = source.toByteArray().size + 599L)).document)
        assertNull(read(archive, ResourceArchiveLimits(maxMembers = 1)).document)
        assertNull(read(utf8("{\"x\":" + "[".repeat(65) + "0" + "]".repeat(65) + "}")).document)
    }

    @Test fun cancellationClosesInputAndRemovesOnlyOwnedFiles() = staging { directory ->
        var closed = false
        var checks = 0
        val input = object : ByteArrayInputStream(zip("README.md" to utf8("a".repeat(50000)))) {
            override fun close() { closed = true; super.close() }
        }
        try {
            ResourceArchiveImport.read(input, directory, checkCancelled = { if (++checks >= 5) throw CancellationException("cancel") })
            fail("Expected cancellation")
        } catch (_: CancellationException) { assertTrue(closed) }
    }

    private fun png(vararg cards: Pair<String, String>): ByteArray = ByteArrayOutputStream().also { output ->
        val writer = DataOutputStream(output)
        writer.write(CharacterCardPng.signature)
        fun chunk(name: String, data: ByteArray) {
            val type = name.toByteArray(Charsets.ISO_8859_1)
            writer.writeInt(data.size); writer.write(type); writer.write(data)
            writer.writeInt(CRC32().apply { update(type); update(data) }.value.toInt())
        }
        val ihdr = ByteArrayOutputStream().also { DataOutputStream(it).apply { writeInt(1); writeInt(1); write(byteArrayOf(8, 6, 0, 0, 0)) } }.toByteArray()
        chunk("IHDR", ihdr)
        cards.forEach { (key, data) -> chunk("tEXt", key.toByteArray() + byteArrayOf(0) + Base64.getEncoder().encode(utf8(data))) }
        chunk("IDAT", ByteArrayOutputStream().also { DeflaterOutputStream(it).use { deflater -> deflater.write(byteArrayOf(0, 0, 0, 0, 0xff.toByte())) } }.toByteArray())
        chunk("IEND", byteArrayOf())
    }.toByteArray()
}
