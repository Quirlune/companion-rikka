package dev.gray.companion.core

import java.io.File
import java.io.InputStream
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.zip.ZipInputStream
import kotlinx.serialization.json.*

/** Bounded transport preparation. Publication and all typed resource validation remain with the caller. */
object ResourceArchiveImport {
    const val FORMAT = "companion.bundle.v1"
    private val kinds = linkedMapOf("character" to "characters", "mainCall" to "mainCalls", "package" to "packages",
        "group" to "groups", "persona" to "personas", "tag" to "tags", "profile" to "profiles", "connection" to "connections", "extension" to "extensions")

    fun read(input: InputStream, stagingDirectory: File, fileName: String = "", limits: ResourceArchiveLimits = ResourceArchiveLimits(),
             checkCancelled: () -> Unit = {}, prepareAvatar: (ByteArray, String) -> AvatarImage = ::portableAvatar): ResourceArchiveResult {
        val budget = ResourceArchiveBudget(limits, checkCancelled)
        val ownedFiles = mutableListOf<File>()
        var ownedDirectory: File? = null
        var count = 0
        val issues = mutableListOf<Issue>()
        try {
            input.buffered().use { stream ->
                checkCancelled()
                archiveRequire(stagingDirectory.isDirectory || stagingDirectory.mkdirs(), "import", "无法创建导入暂存目录")
                val directory = File(stagingDirectory, "companion-import-${UUID.randomUUID()}")
                archiveRequire(directory.mkdir(), "import", "无法建立私有导入暂存目录")
                ownedDirectory = directory
                stream.mark(8)
                val prefix = ByteArray(8)
                var prefixCount = 0
                while (prefixCount < prefix.size) { val n = stream.read(prefix, prefixCount, prefix.size - prefixCount); if (n < 0) break; if (n > 0) prefixCount += n }
                stream.reset()
                val zip = prefixCount >= 4 && prefix[0] == 80.toByte() && prefix[1] == 75.toByte() &&
                    ((prefix[2] == 3.toByte() && prefix[3] == 4.toByte()) || (prefix[2] == 5.toByte() && prefix[3] == 6.toByte()))
                val members = linkedMapOf<String, ResourceArchiveMember>()
                fun stage(source: InputStream, path: String): ResourceArchiveMember {
                    val file = File(directory, "member-${ownedFiles.size}")
                    ownedFiles += file
                    var size = 0L
                    val maximum = maxOf(limits.maxJsonBytes, limits.maxTextBytes, limits.maxImageBytes).toLong()
                    file.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            checkCancelled()
                            val n = source.read(buffer)
                            if (n < 0) break
                            if (n == 0) continue
                            budget.expanded(n, path)
                            size += n
                            archiveRequire(size <= maximum, path, "单个文件超过支持的文本、JSON 或图片限额")
                            output.write(buffer, 0, n)
                        }
                    }
                    return ResourceArchiveMember(path, file, size)
                }
                if (zip) {
                    val names = mutableMapOf<String, Boolean>()
                    ZipInputStream(stream, Charsets.UTF_8).use { archive ->
                        while (true) {
                            checkCancelled()
                            val member = archive.nextEntry ?: break
                            count++
                            archiveRequire(count <= limits.maxMembers, "archive", "包内成员超过 ${limits.maxMembers} 个")
                            val directoryEntry = member.isDirectory
                            val path = archivePath(if (directoryEntry) member.name.dropLast(1) else member.name)
                            val folded = archiveFold(path)
                            archiveRequire(folded !in names, path, "包内路径重复或仅大小写不同")
                            archiveRequire(names.none { (name, isDirectory) -> (!isDirectory && folded.startsWith("$name/")) || (!directoryEntry && name.startsWith("$folded/")) }, path, "包内文件与目录路径冲突")
                            names[folded] = directoryEntry
                            if (directoryEntry) archiveRequire(archive.read() == -1, path, "目录成员不能携带内容")
                            else members[path] = stage(archive, path)
                            archive.closeEntry()
                        }
                    }
                    archiveRequire(members.isNotEmpty(), "archive", "ZIP 内没有可导入文件")
                    val result = Assembly(members, budget, issues, prepareAvatar).assemble()
                    return ResourceArchiveResult(result, issues, count, budget.expandedBytes)
                }
                archiveRequire(!fileName.endsWith(".zip", true) && !fileName.endsWith(".charx", true), "import", "文件不是支持的 ZIP")
                val png = prefixCount == 8 && prefix.contentEquals(CharacterCardPng.signature)
                val member = stage(stream, if (png) "character.png" else "resource.json")
                count = 1
                val result = if (png) {
                    val parsed = CharacterCardPng.read(member, budget)
                    issues += parsed.warnings
                    val document = parsed.document ?: throw ResourceArchiveProblem(member.path, "PNG 不含角色卡元数据")
                    CharacterCardImport.convert(document, member.path, null, checkedAvatar(parsed.avatarBytes, "image/png", member.path, budget, prepareAvatar), budget, issues)
                } else {
                    val document = member.json(budget)
                    if (CharacterCardImport.recognizes(document)) CharacterCardImport.convert(document, member.path, null, null, budget, issues) else document
                }
                return ResourceArchiveResult(result, issues, count, budget.expandedBytes)
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: ResourceArchiveProblem) {
            return ResourceArchiveResult(null, issues + Issue(failure.path, failure.message), count, budget.expandedBytes)
        } catch (failure: Exception) {
            return ResourceArchiveResult(null, issues + Issue("import", "读取失败：${failure.message ?: failure.javaClass.simpleName}"), count, budget.expandedBytes)
        } finally {
            ownedFiles.forEach { it.delete() }
            ownedDirectory?.delete()
        }
    }

    private data class Descriptor(val path: String, val kind: String, val id: String?)
    private class Assembly(val members: Map<String, ResourceArchiveMember>, val budget: ResourceArchiveBudget,
                           val issues: MutableList<Issue>, val prepareAvatar: (ByteArray, String) -> AvatarImage) {
        private val consumed = mutableSetOf<String>()
        private val primaryPaths = mutableSetOf<String>()
        private var descriptors = emptyMap<String, Descriptor>()
        private val fragments = mutableListOf<JsonObject>()
        private var native: JsonObject? = null
        private var label = "ZIP 内容包"

        fun assemble(): JsonObject {
            val manifest = members["manifest.json"]
            if (manifest != null) {
                consumed += manifest.path
                val root = manifest.json(budget)
                archiveRequire(root.keys.all { it in setOf("format", "label", "files") }, manifest.path, "manifest 含不支持的字段")
                archiveRequire(root["format"].archiveString("manifest.format") == FORMAT, "manifest.format", "不支持的内容包版本")
                root["label"]?.let { label = it.archiveString("manifest.label") }
                val files = root["files"] as? JsonArray ?: throw ResourceArchiveProblem("manifest.files", "须列出文件数组")
                val list = files.mapIndexed { index, value ->
                    val at = "manifest.files[$index]"
                    val item = value as? JsonObject ?: throw ResourceArchiveProblem(at, "文件说明须为对象")
                    archiveRequire(item.keys.all { it in setOf("path", "kind", "id") }, at, "文件说明含不支持的字段")
                    val path = archivePath(item["path"].archiveString("$at.path"), "$at.path")
                    archiveRequire(path != "manifest.json" && members.containsKey(path), "$at.path", "引用的文件不存在或不能引用 manifest 自身：$path")
                    val kind = item["kind"].archiveString("$at.kind")
                    archiveRequire(kind in kinds || kind in setOf("config", "native", "character-card", "text", "avatar", "documentation"), "$at.kind", "未知文件类型：$kind")
                    val id = item["id"]?.archiveString("$at.id")
                    archiveRequire(id == null || (id.isNotBlank() && (kind in kinds || kind == "character-card")), "$at.id", "仅独立资源或角色卡可指定非空 id")
                    Descriptor(path, kind, id)
                }
                archiveRequire(list.map { archiveFold(it.path) }.distinct().size == list.size, "manifest.files", "文件说明重复")
                descriptors = list.associateBy { it.path }
                list.filter { it.kind !in setOf("text", "avatar", "documentation") }.forEach(::primary)
            } else {
                members.values.filter { it.path.endsWith(".json", true) }.forEach { member ->
                    val folderKind = kinds.entries.firstOrNull { member.path.startsWith("${it.value}/") }?.key
                    primary(Descriptor(member.path, folderKind ?: "auto", null))
                }
                members.values.filter { it.path !in consumed && it.path.endsWith(".png", true) }.forEach { primary(Descriptor(it.path, "character-card", null)) }
            }
            for (member in members.values.filter { it.path !in consumed }) {
                val kind = descriptors[member.path]?.kind
                val folded = archiveFold(member.path)
                val documentation = kind == "documentation" || (manifest == null && (folded in setOf("readme.md", "readme.txt") || folded.startsWith("docs/")) && (folded.endsWith(".md") || folded.endsWith(".txt")))
                archiveRequire(documentation, member.path, "文件未被引用或不支持其用途；请在 manifest 中明确归类并引用，不会静默忽略")
                strictArchiveText(member.bytes(budget.limits.maxTextBytes, budget.checkCancelled), member.path)
                issues += Issue(member.path, "说明文档仅供阅读，未导入资源", false)
                consumed += member.path
            }
            native?.let {
                archiveRequire(fragments.isEmpty(), "archive", "原生备份须单独作为主文档；请将其他资源合并进备份，或分别导入")
                return it
            }
            archiveRequire(fragments.isNotEmpty(), "archive", "包内没有资源或配置文档")
            val resources = linkedMapOf<String, MutableList<JsonElement>>()
            val commands = mutableListOf<JsonElement>()
            val ids = mutableSetOf<Pair<String, String>>()
            fragments.forEach { fragment ->
                archiveRequire(fragment.keys.all { it in setOf("format", "label", "resources", "commands") }, "config", "配置文档含不支持的字段")
                fragment["resources"]?.let { value ->
                    val table = value as? JsonObject ?: throw ResourceArchiveProblem("config.resources", "资源表须为对象")
                    table.forEach { (kind, items) ->
                        archiveRequire(kind in kinds.values, "config.resources.$kind", "未知资源类型")
                        val array = items as? JsonArray ?: throw ResourceArchiveProblem("config.resources.$kind", "资源须为数组")
                        array.forEach { item ->
                            val obj = item as? JsonObject ?: throw ResourceArchiveProblem("config.resources.$kind", "资源须为对象")
                            val id = obj["id"]?.archiveString("config.resources.$kind.id") ?: newId()
                            archiveRequire(ids.add(kind to id), "config.resources.$kind.$id", "多个文件包含相同资源 ID；请使用显式 commands 修改")
                            resources.getOrPut(kind) { mutableListOf() } += JsonObject(obj + ("id" to JsonPrimitive(id)))
                        }
                    }
                }
                fragment["commands"]?.let { value -> commands.addAll(value as? JsonArray ?: throw ResourceArchiveProblem("config.commands", "命令须为数组")) }
            }
            return buildJsonObject {
                put("format", ConfigurationCommands.FORMAT); put("label", label)
                put("resources", JsonObject(resources.mapValues { JsonArray(it.value) }))
                if (commands.isNotEmpty()) put("commands", JsonArray(commands))
            }
        }

        private fun primary(descriptor: Descriptor) {
            budget.checkCancelled()
            val member = members.getValue(descriptor.path)
            archiveRequire(consumed.add(member.path), member.path, "主文档不能同时作为另一个文件的附件")
            primaryPaths += member.path
            if (member.path.endsWith(".png", true)) {
                archiveRequire(descriptor.kind == "character-card", member.path, "PNG 主文档须为 character-card")
                val parsed = CharacterCardPng.read(member, budget)
                issues += parsed.warnings
                val document = parsed.document ?: throw ResourceArchiveProblem(member.path, "PNG 不含角色卡元数据；普通头像须被 $AVATAR 引用")
                fragments += CharacterCardImport.convert(document, member.path, descriptor.id,
                    checkedAvatar(parsed.avatarBytes, "image/png", member.path, budget, prepareAvatar), budget, issues)
                return
            }
            val document = resolve(member.json(budget), member.path) as JsonObject
            val inferred = when {
                document["format"] == JsonPrimitive(ConfigurationCommands.FORMAT) -> "config"
                CharacterCardImport.recognizes(document) -> "character-card"
                "schemaVersion" in document || document.keys.any { it in kinds.values || it == "sessions" || it == "savedChats" || it == "promptTags" } -> "native"
                else -> "unknown"
            }
            val kind = if (descriptor.kind == "auto") inferred else descriptor.kind
            when (kind) {
                "config" -> {
                    archiveRequire(inferred == "config", member.path, "config 文件缺少 companion.config.v1 格式")
                    fragments += document
                }
                "native" -> {
                    archiveRequire(native == null, member.path, "一个 ZIP 只能包含一份原生备份")
                    native = document
                }
                "character-card" -> fragments += CharacterCardImport.convert(document, member.path, descriptor.id, null, budget, issues)
                in kinds -> {
                    val existingId = document["id"]?.archiveString("${member.path}.id")
                    archiveRequire(descriptor.id == null || existingId == null || descriptor.id == existingId, member.path, "manifest id 与资源 id 不一致")
                    val item = JsonObject(document + ("id" to JsonPrimitive(descriptor.id ?: existingId ?: newId())))
                    fragments += buildJsonObject { put("format", ConfigurationCommands.FORMAT); put("resources", buildJsonObject { put(kinds.getValue(kind), JsonArray(listOf(item))) }) }
                }
                else -> throw ResourceArchiveProblem(member.path, "无法确定 JSON 资源类型；请使用 manifest.files 的 kind 或标准资源目录")
            }
        }

        private fun resolve(element: JsonElement, at: String): JsonElement {
            budget.checkCancelled()
            return when (element) {
                is JsonObject -> {
                    val marker = element.keys.firstOrNull { it == TEXT || it == AVATAR }
                    if (marker == null) JsonObject(element.mapValues { (key, value) -> resolve(value, "$at.$key") }) else {
                        archiveRequire(element.size == 1, at, "文件标记必须是仅含 $marker 的对象")
                        val path = archivePath(element[marker].archiveString(at), at)
                        val member = members[path] ?: throw ResourceArchiveProblem(at, "引用的包内文件不存在：$path")
                        val expected = if (marker == TEXT) "text" else "avatar"
                        archiveRequire(descriptors[path] == null || descriptors[path]?.kind == expected, at, "文件标记与 manifest 类型不一致：$path")
                        archiveRequire(path != "manifest.json", at, "不能将 manifest 作为附件")
                        archiveRequire(path !in primaryPaths, at, "主文档不能同时作为附件：$path")
                        consumed += path
                        if (marker == TEXT) {
                            budget.materialized(member.size, at)
                            JsonPrimitive(strictArchiveText(member.bytes(budget.limits.maxTextBytes, budget.checkCancelled), path))
                        } else {
                            val avatar = if (path.endsWith(".png", true)) {
                                val parsed = CharacterCardPng.read(member, budget)
                                archiveRequire(parsed.document == null, path, "带角色元数据的 PNG 须作为角色卡导入，不能仅取头像而丢失角色数据")
                                checkedAvatar(parsed.avatarBytes, "image/png", path, budget, prepareAvatar)
                            } else {
                                archiveRequire(path.endsWith(".jpg", true) || path.endsWith(".jpeg", true), path, "头像仅支持 PNG/JPEG")
                                checkedAvatar(member.bytes(budget.limits.maxImageBytes, budget.checkCancelled), "image/jpeg", path, budget, prepareAvatar)
                            }
                            LibraryFormat.json.encodeToJsonElement(AvatarImage.serializer(), avatar)
                        }
                    }
                }
                is JsonArray -> JsonArray(element.mapIndexed { index, value -> resolve(value, "$at[$index]") })
                else -> element
            }
        }
    }

    private const val TEXT = "\$text"
    private const val AVATAR = "\$avatar"
    private fun checkedAvatar(bytes: ByteArray, mediaType: String, path: String, budget: ResourceArchiveBudget,
                              prepare: (ByteArray, String) -> AvatarImage): AvatarImage {
        budget.checkCancelled()
        archiveRequire(if (mediaType == "image/png") bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(CharacterCardPng.signature)
            else bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte(), path, "头像实际内容不是声明的 PNG/JPEG")
        val avatar = prepare(bytes, mediaType)
        archiveRequire(avatar.decodedBytes() != null, path, "头像转换未产生有效的 PNG/JPEG 缩略图")
        budget.materialized(avatar.base64.length.toLong(), path)
        return avatar
    }

    private fun portableAvatar(bytes: ByteArray, mediaType: String): AvatarImage {
        archiveRequire(bytes.size <= AvatarImage.MAX_BYTES, "avatar", "此环境无法缩小头像；请提供不超过 32 KiB 的 PNG/JPEG")
        return AvatarImage(mediaType, Base64.getEncoder().encodeToString(bytes))
    }
}
