package dev.gray.companion.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Text files belonging to one Agent chat. The caller supplies its private files root. */
class AgentFiles(root: File) {
    private val lock = Mutex()
    private val base: Path = root.canonicalFile.toPath().also { Files.createDirectories(it) }.toRealPath()

    suspend fun execute(operation: String, args: JsonObject): JsonObject = try {
        withContext(Dispatchers.IO) { lock.withLock { dispatch(operation, args) } }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (failure: FileFault) { problem(failure.code, failure.message.orEmpty()) }
    catch (_: Exception) { problem("FILE_FAILED", "文件操作未完成，请重读后重试") }

    private fun dispatch(operation: String, args: JsonObject): JsonObject = when (operation) {
        "file.list" -> list(args)
        "file.read" -> read(args)
        "file.write" -> write(args)
        "file.edit" -> edit(args)
        "file.move" -> move(args)
        "file.delete" -> delete(args)
        else -> problem("UNKNOWN_TOOL", "未知文件工具")
    }

    private fun list(args: JsonObject): JsonObject {
        val dir = resolve(args.optionalString("path") ?: "", directory = true)
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) throw FileFault("NOT_FOUND", "目录不存在")
        val entries = Files.list(dir).use { stream -> stream.iterator().asSequence().toList().sortedBy { it.fileName.toString() } }
        if (entries.size > MAX_ENTRIES) throw FileFault("LIMIT", "目录项目过多")
        return success(buildJsonObject {
            put("path", base.relativize(dir).toString().replace('\\', '/'))
            put("entries", buildJsonArray { entries.forEach { entry ->
                checked(entry)
                add(buildJsonObject {
                    put("name", entry.fileName.toString())
                    put("directory", Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS))
                    if (Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) put("bytes", Files.size(entry))
                })
            } })
        })
    }

    private fun read(args: JsonObject): JsonObject {
        val path = resolve(args.requiredString("path"))
        val bytes = readBytes(path)
        return success(fileData(path, decode(bytes), hash(bytes)))
    }

    private fun write(args: JsonObject): JsonObject {
        val path = resolve(args.requiredString("path"))
        val text = args.requiredString("text")
        val expected = args.expectedHash()
        val old = existingBytes(path)
        compare(old, expected)
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        checkSize(bytes)
        checkQuota(path, old?.size?.toLong() ?: 0, bytes.size.toLong(), old == null)
        atomicWrite(path, bytes)
        return success(fileData(path, text, hash(bytes), includeText = false))
    }

    private fun edit(args: JsonObject): JsonObject {
        val path = resolve(args.requiredString("path"))
        val oldBytes = readBytes(path)
        compare(oldBytes, args.expectedHash(requiredString = true))
        val oldText = args.requiredString("oldText").takeIf { it.isNotEmpty() }
            ?: throw FileFault("INVALID", "oldText 不能为空")
        val replacement = args.requiredString("newText")
        val text = decode(oldBytes)
        val count = Regex(Regex.escape(oldText)).findAll(text).count()
        if (count == 0) throw FileFault("STALE", "待替换文本不存在，请重读文件")
        val replaceAll = args.boolean("replaceAll") ?: false
        if (count > 1 && !replaceAll) throw FileFault("AMBIGUOUS", "文本出现多次；请指定 replaceAll")
        val result = if (replaceAll) text.replace(oldText, replacement) else text.replaceFirst(oldText, replacement)
        val bytes = result.toByteArray(StandardCharsets.UTF_8)
        checkSize(bytes)
        checkQuota(path, oldBytes.size.toLong(), bytes.size.toLong(), false)
        atomicWrite(path, bytes)
        return success(buildJsonObject { put("path", relative(path)); put("hash", hash(bytes)); put("replacements", if (replaceAll) count else 1) })
    }

    private fun move(args: JsonObject): JsonObject {
        val source = resolve(args.requiredString("from"))
        val destination = resolve(args.requiredString("to"))
        if (source == destination) throw FileFault("INVALID", "源和目标相同")
        val bytes = readBytes(source)
        compare(bytes, args.expectedHash(requiredString = true))
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) throw FileFault("EXISTS", "目标文件已存在")
        Files.createDirectories(destination.parent)
        checked(destination)
        try { Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE) }
        catch (_: AtomicMoveNotSupportedException) { throw FileFault("UNSUPPORTED", "此存储位置不支持原子移动") }
        return success(buildJsonObject { put("from", relative(source)); put("to", relative(destination)); put("hash", hash(bytes)) })
    }

    private fun delete(args: JsonObject): JsonObject {
        val path = resolve(args.requiredString("path"))
        val bytes = readBytes(path)
        compare(bytes, args.expectedHash(requiredString = true))
        Files.delete(path)
        return success(buildJsonObject { put("path", relative(path)); put("deletedHash", hash(bytes)) })
    }

    private fun resolve(raw: String, directory: Boolean = false): Path {
        if (raw.length > 256 || raw.contains('\\') || raw.contains(':') || raw.any { it.code < 32 })
            throw FileFault("INVALID_PATH", "路径无效")
        if (raw.isEmpty() && !directory) throw FileFault("INVALID_PATH", "需要相对文件路径")
        val parts = if (raw.isEmpty()) emptyList() else raw.split('/')
        if (parts.size > 8 || parts.any { it.isEmpty() || it == "." || it == ".." || it.startsWith('.') })
            throw FileFault("INVALID_PATH", "仅允许工作区内的相对路径")
        val path = parts.fold(base) { current, part -> current.resolve(part) }.normalize()
        checked(path)
        return path
    }

    /** Check each existing component: canonical containment also catches junctions and symlinks. */
    private fun checked(path: Path) {
        if (!path.startsWith(base)) throw FileFault("INVALID_PATH", "路径超出工作区")
        var cursor = base
        base.relativize(path).forEach { part ->
            cursor = cursor.resolve(part)
            if (Files.isSymbolicLink(cursor)) throw FileFault("INVALID_PATH", "符号链接不可访问")
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS) && !cursor.toRealPath().startsWith(base))
                throw FileFault("INVALID_PATH", "路径超出工作区")
        }
    }

    private fun existingBytes(path: Path): ByteArray? {
        checked(path)
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null
        return readBytes(path)
    }

    private fun readBytes(path: Path): ByteArray {
        checked(path)
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw FileFault("NOT_FOUND", "文本文件不存在")
        if (Files.size(path) > MAX_FILE_BYTES) throw FileFault("LIMIT", "文件超过读取限额")
        val bytes = Files.readAllBytes(path)
        checkSize(bytes)
        decode(bytes)
        return bytes
    }

    private fun decode(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) { throw FileFault("INVALID_TEXT", "仅支持 UTF-8 文本文件") }

    private fun checkSize(bytes: ByteArray) {
        if (bytes.size > MAX_FILE_BYTES) throw FileFault("LIMIT", "单文件最多 256 KiB")
    }

    private fun checkQuota(target: Path, previous: Long, next: Long, creating: Boolean) {
        var bytes = 0L
        var files = 0
        Files.walk(base).use { stream -> stream.forEach { path ->
            checked(path)
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) { files++; bytes += Files.size(path) }
        } }
        if (files + (if (creating) 1 else 0) > MAX_FILES || bytes - previous + next > MAX_TOTAL_BYTES)
            throw FileFault("LIMIT", "工作区文件数或总量已达上限")
    }

    private fun atomicWrite(path: Path, bytes: ByteArray) {
        Files.createDirectories(path.parent)
        checked(path)
        val temp = Files.createTempFile(path.parent, ".agenttmp", ".tmp")
        try {
            Files.write(temp, bytes)
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { throw FileFault("UNSUPPORTED", "此存储位置不支持原子写入") }
        } finally { Files.deleteIfExists(temp) }
    }

    private fun compare(bytes: ByteArray?, expected: String?) {
        if (bytes == null && expected != null || bytes != null && hash(bytes) != expected)
            throw FileFault("STALE", "文件已改变，请重读 hash 后操作")
    }

    private fun fileData(path: Path, text: String, digest: String, includeText: Boolean = true): JsonObject = buildJsonObject {
        put("path", relative(path)); put("hash", digest); put("bytes", text.toByteArray(StandardCharsets.UTF_8).size)
        if (includeText) put("text", text)
    }

    private fun relative(path: Path) = base.relativize(path).toString().replace('\\', '/')

    private fun JsonObject.requiredString(key: String): String = optionalString(key)
        ?: throw FileFault("INVALID", "$key 须为字符串")
    private fun JsonObject.optionalString(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.expectedHash(requiredString: Boolean = false): String? {
        if ("expectedHash" !in this) throw FileFault("INVALID", "必须提供 expectedHash；新文件使用 null")
        val value = this["expectedHash"]
        if (value == JsonNull && !requiredString) return null
        val digest = optionalString("expectedHash") ?: throw FileFault("INVALID", "expectedHash 须为 SHA-256")
        if (!digest.matches(Regex("[a-f0-9]{64}"))) throw FileFault("INVALID", "expectedHash 须为 SHA-256")
        return digest
    }
    private fun JsonObject.boolean(key: String): Boolean? = when (this[key]) {
        null -> null
        JsonPrimitive(true) -> true
        JsonPrimitive(false) -> false
        else -> throw FileFault("INVALID", "$key 须为布尔值")
    }

    private class FileFault(val code: String, override val message: String) : RuntimeException(message)

    companion object {
        private const val MAX_FILE_BYTES = 256 * 1024
        private const val MAX_TOTAL_BYTES = 8 * 1024 * 1024
        private const val MAX_FILES = 128
        private const val MAX_ENTRIES = 128

        private fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        private fun success(data: JsonObject) = buildJsonObject { put("status", "OK"); put("data", data) }
        private fun problem(code: String, message: String) = buildJsonObject {
            put("status", code); put("retryable", code == "STALE")
            put("message", message)
        }
    }
}
