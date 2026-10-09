package dev.gray.companion.core

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.zip.CRC32
import kotlinx.serialization.json.JsonObject

internal data class CharacterCardPngData(val document: JsonObject?, val avatarBytes: ByteArray, val warnings: List<Issue>)

/** CCv2 chara / CCv3 ccv3 use base64 UTF-8 JSON in tEXt; ccv3 takes precedence. */
internal object CharacterCardPng {
    val signature = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)

    fun read(member: ResourceArchiveMember, budget: ResourceArchiveBudget): CharacterCardPngData {
        archiveRequire(member.size <= budget.limits.maxImageBytes, member.path, "PNG 超过图片限额")
        val avatar = ByteArrayOutputStream()
        val writer = DataOutputStream(avatar)
        val metadata = linkedMapOf<String, ByteArray>()
        val warnings = mutableListOf<Issue>()
        member.file.inputStream().buffered().use { stream ->
            val input = DataInputStream(stream)
            val header = ByteArray(8)
            input.readFully(header)
            archiveRequire(header.contentEquals(signature), member.path, "PNG 签名无效")
            writer.write(header)
            var first = true
            var imageData = false
            var ended = false
            var consumed = 8L
            val buffer = ByteArray(8192)
            while (!ended) {
                budget.checkCancelled()
                val length = input.readInt()
                archiveRequire(length >= 0 && length.toLong() <= member.size - consumed - 12L, member.path, "PNG 数据块长度无效或已截断")
                val type = ByteArray(4)
                input.readFully(type)
                val name = String(type, StandardCharsets.ISO_8859_1)
                archiveRequire(type.all { (it.toInt() and 255) in 65..90 || (it.toInt() and 255) in 97..122 }, member.path, "PNG 数据块类型无效")
                if (first) archiveRequire(name == "IHDR" && length == 13, member.path, "PNG 缺少有效 IHDR")
                else archiveRequire(name != "IHDR", member.path, "PNG 含重复 IHDR")
                first = false
                val text = name in setOf("tEXt", "zTXt", "iTXt")
                val captured = if (text) ByteArrayOutputStream(minOf(length, 8192)) else null
                // Text is removed from the thumbnail, including the potentially large card JSON.
                if (!text) { writer.writeInt(length); writer.write(type) }
                val crc = CRC32().apply { update(type) }
                var remaining = length
                while (remaining > 0) {
                    budget.checkCancelled()
                    val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                    archiveRequire(count > 0, member.path, "PNG 数据块已截断")
                    crc.update(buffer, 0, count)
                    if (captured != null) captured.write(buffer, 0, count) else writer.write(buffer, 0, count)
                    remaining -= count
                }
                val storedCrc = input.readInt()
                archiveRequire(crc.value == (storedCrc.toLong() and 0xffffffffL), member.path, "PNG 数据块校验失败：$name")
                if (!text) writer.writeInt(storedCrc)
                consumed += length.toLong() + 12L
                if (name == "IDAT") imageData = true
                if (name == "IEND") { archiveRequire(length == 0 && imageData, member.path, "PNG 缺少图像数据或 IEND 无效"); ended = true }
                captured?.toByteArray()?.let { data ->
                    val separator = data.indexOf(0)
                    archiveRequire(separator in 1..79, member.path, "PNG 文本关键词无效")
                    val keyword = String(data, 0, separator, StandardCharsets.ISO_8859_1).lowercase(java.util.Locale.ROOT)
                    archiveRequire(!keyword.startsWith("chara-ext-asset_"), member.path, "PNG 含额外嵌入资产；当前仅支持角色数据与头像，请拆出为支持的文件")
                    if (keyword == "chara" || keyword == "ccv3") {
                        archiveRequire(name == "tEXt", member.path, "角色元数据须使用规范的 tEXt 数据块；暂不支持 $name")
                        archiveRequire(keyword !in metadata, member.path, "PNG 含重复的 $keyword 角色元数据")
                        archiveRequire(data.size - separator - 1 <= (budget.limits.maxJsonBytes.toLong() + 2) / 3 * 4,
                            member.path, "PNG 角色 JSON 超过限额")
                        val decoded = try { Base64.getDecoder().decode(data.copyOfRange(separator + 1, data.size)) }
                        catch (_: IllegalArgumentException) { throw ResourceArchiveProblem(member.path, "PNG 角色元数据不是有效 Base64") }
                        archiveRequire(decoded.size <= budget.limits.maxJsonBytes, member.path, "PNG 角色 JSON 超过限额")
                        budget.materialized(decoded.size.toLong(), member.path)
                        metadata[keyword] = decoded
                    }
                }
            }
            archiveRequire(input.read() == -1, member.path, "PNG 的 IEND 后含未识别数据")
        }
        if (metadata.size > 1) warnings += Issue(member.path, "PNG 同时含 chara 与 ccv3；按规范使用 ccv3，兼容副本不重复导入", false)
        val selected = metadata["ccv3"] ?: metadata["chara"]
        return CharacterCardPngData(selected?.let { archiveJson(strictArchiveText(it, member.path), member.path) }, avatar.toByteArray(), warnings)
    }
}
