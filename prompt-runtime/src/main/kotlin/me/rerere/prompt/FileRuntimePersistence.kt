package me.rerere.prompt

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** One store owns one file. Corrupt/unsupported files are surfaced, never silently reset. */
class FileRuntimePersistence(private val file: File) : RuntimePersistence {
    private val json = Json { prettyPrint = true }

    override suspend fun load(): RuntimeState? =
        withContext(Dispatchers.IO) {
            if (file.exists()) json.decodeFromString<RuntimeState>(file.readText()) else null
        }

    override suspend fun save(state: RuntimeState) =
        withContext(Dispatchers.IO) {
            val parent = file.absoluteFile.parentFile
            check(parent.isDirectory || parent.mkdirs()) { "Cannot create runtime directory" }
            val temporary = File.createTempFile("runtime-", ".tmp", parent)
            try {
                FileOutputStream(temporary).use { output ->
                    output.write(json.encodeToString(state).toByteArray(Charsets.UTF_8))
                    output.fd.sync()
                }
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
                Unit
            } finally {
                temporary.delete()
            }
        }
}
