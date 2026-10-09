package dev.gray.companion.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.text.Normalizer
import java.util.Locale
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** Limits apply to actual bytes, including repeated expansions of the same text member. */
data class ResourceArchiveLimits(
    val maxMembers: Int = 1024,
    val maxExpandedBytes: Long = 256L * 1024 * 1024,
    val maxJsonBytes: Int = 8 * 1024 * 1024,
    val maxTextBytes: Int = 32 * 1024 * 1024,
    val maxImageBytes: Int = 32 * 1024 * 1024,
    val maxMaterializedBytes: Long = 64L * 1024 * 1024,
) {
    init {
        require(maxMembers > 0 && maxExpandedBytes > 0 && maxJsonBytes > 0 && maxTextBytes > 0 &&
            maxImageBytes > 0 && maxMaterializedBytes > 0) { "导入限额必须大于零" }
    }
}

data class ResourceArchiveResult(
    val document: JsonObject?,
    val issues: List<Issue> = emptyList(),
    val memberCount: Int = 0,
    val expandedBytes: Long = 0,
)

internal class ResourceArchiveProblem(val path: String, override val message: String) : RuntimeException(message)
internal fun archiveRequire(condition: Boolean, path: String, message: String) {
    if (!condition) throw ResourceArchiveProblem(path, message)
}

internal class ResourceArchiveBudget(val limits: ResourceArchiveLimits, val checkCancelled: () -> Unit) {
    var expandedBytes = 0L
        private set
    var materializedBytes = 0L
        private set

    fun expanded(bytes: Int, path: String) {
        archiveRequire(bytes.toLong() <= limits.maxExpandedBytes - expandedBytes, path, "总解压内容超过 ${limits.maxExpandedBytes} 字节")
        expandedBytes += bytes
    }

    fun materialized(bytes: Long, path: String) {
        archiveRequire(bytes <= limits.maxMaterializedBytes - materializedBytes, path, "JSON 与提示词展开总量超过 ${limits.maxMaterializedBytes} 字节；请拆分导入")
        materializedBytes += bytes
    }
}

/** No member name is used as a disk path. Rejected aliases must not bypass duplicate checks. */
internal fun archivePath(value: String, at: String = value): String {
    archiveRequire(value.isNotEmpty() && value.length <= 1024, at, "包内路径不能为空或超过 1024 字符")
    archiveRequire(!value.startsWith('/') && '\\' !in value && ':' !in value &&
        value.none { it.code < 32 || it.code == 127 }, at, "仅允许以 / 分隔的包内相对路径；不支持绝对路径、URL 或反斜杠")
    archiveRequire(value == Normalizer.normalize(value, Normalizer.Form.NFC), at, "包内路径须使用统一的 NFC Unicode 形式")
    archiveRequire(value.split('/').none { it.isEmpty() || it == "." || it == ".." || it.endsWith(' ') || it.endsWith('.') }, at,
        "包内路径不能含空层级、点路径、尾随空格或尾随点")
    return value
}

internal fun archiveFold(value: String): String = value.lowercase(Locale.ROOT)

internal fun readArchiveBytes(input: InputStream, maximum: Int, path: String, checkCancelled: () -> Unit): ByteArray {
    val output = ByteArrayOutputStream(minOf(maximum, 8192))
    val buffer = ByteArray(8192)
    while (true) {
        checkCancelled()
        val count = input.read(buffer)
        if (count < 0) break
        if (count == 0) continue
        archiveRequire(count <= maximum - output.size(), path, "文件超过 $maximum 字节")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

internal fun strictArchiveText(bytes: ByteArray, path: String): String = try {
    StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
} catch (_: java.nio.charset.CharacterCodingException) {
    throw ResourceArchiveProblem(path, "文件必须是有效 UTF-8，不能使用替换字符修复损坏文本")
}

/** Bound nesting before allocating the JSON tree or recursively expanding file markers. */
internal fun archiveJson(text: String, path: String): JsonObject {
    var depth = 0
    var quoted = false
    var escaped = false
    text.forEach { char ->
        if (quoted) {
            if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
        } else when (char) {
            '"' -> quoted = true
            '{', '[' -> { depth++; archiveRequire(depth <= 64, path, "JSON 嵌套不能超过 64 层") }
            '}', ']' -> depth--
        }
    }
    return try {
        LibraryFormat.json.parseToJsonElement(text.removePrefix("\uFEFF")) as? JsonObject
            ?: throw ResourceArchiveProblem(path, "JSON 文件根必须是对象")
    } catch (failure: ResourceArchiveProblem) { throw failure }
    catch (_: IllegalArgumentException) { throw ResourceArchiveProblem(path, "JSON 内容无效") }
}

internal data class ResourceArchiveMember(val path: String, val file: File, val size: Long) {
    fun bytes(maximum: Int, checkCancelled: () -> Unit): ByteArray {
        archiveRequire(size <= maximum, path, "文件超过 $maximum 字节")
        return file.inputStream().use { readArchiveBytes(it, maximum, path, checkCancelled) }
    }

    fun json(budget: ResourceArchiveBudget): JsonObject {
        budget.materialized(size, path)
        return archiveJson(strictArchiveText(bytes(budget.limits.maxJsonBytes, budget.checkCancelled), path), path)
    }
}

internal fun JsonElement?.archiveString(path: String): String =
    (this as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
        ?: throw ResourceArchiveProblem(path, "此字段须为字符串")
