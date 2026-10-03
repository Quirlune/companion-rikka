package dev.gray.companion.core

/** A pure failure from generated content, distinct from an invalid saved configuration. */
class OutputFormatException(message: String, val source: String? = null) : IllegalArgumentException(message)

data class TileContractResult(val value: String, val error: String? = null, val html: String? = null)

object MacroOutputContracts {
    private val directSource = Regex("^\\{\\{\\s*((?:result|macro|state)\\.[\\p{L}\\p{N}_.-]+)\\s*}}$")

    /** Only a direct, current address has one unambiguous producer. */
    fun sourceAddress(tile: MacroTile): String? = directSource.matchEntire(tile.template.trim())
        ?.groupValues?.get(1)?.let(::canonicalWriteAddress)

    fun read(tile: MacroTile, raw: String): TileContractResult {
        val problem = SafePatterns.validate(tile.pattern, tile.captureGroup)
        if (problem != null) return TileContractResult("", "磁贴正则配置无效：$problem")
        if (tile.kind == MacroTileKind.HTML) HtmlTemplate.validate(tile).firstOrNull()?.let {
            return TileContractResult("", "HTML 模板配置无效：${it.message}")
        }
        val extracted = SafePatterns.extract(tile.pattern, raw, tile.captureGroup)
            ?: return TileContractResult("", "磁贴正则未匹配")
        if (tile.kind == MacroTileKind.HTML) {
            val rendered = HtmlTemplate.renderValidated(tile, extracted)
            return TileContractResult(extracted, rendered.error, rendered.html)
        }
        if (tile.kind == MacroTileKind.NUMBER || tile.kind == MacroTileKind.PROGRESS) {
            val number = extracted.trim().toDoubleOrNull()
            if (number == null || !number.isFinite()) return TileContractResult(extracted, "需要有限数值")
            if (tile.kind == MacroTileKind.PROGRESS && number !in tile.minimum..tile.maximum)
                return TileContractResult(extracted, "进度值超出范围")
        }
        if (tile.kind == MacroTileKind.STATUS) {
            val values = StatusFields.readAll(extracted, tile.fields.map { it.key })
            if (tile.fields.isEmpty() || tile.fields.any { field -> values[field.key].isNullOrBlank() })
                return TileContractResult(extracted, "状态 XML 缺少有效字段")
            tile.fields.forEach { field ->
                if (field.kind == MacroTileKind.NUMBER || field.kind == MacroTileKind.PROGRESS) {
                    val number = values[field.key]?.toDoubleOrNull()
                    if (number == null || !number.isFinite())
                        return TileContractResult(extracted, "状态字段 ${field.title.ifBlank { field.key }} 需要有限数值")
                    if (field.kind == MacroTileKind.PROGRESS && number !in field.minimum..field.maximum)
                        return TileContractResult(extracted, "状态字段 ${field.title.ifBlank { field.key }} 超出范围")
                }
            }
        }
        return TileContractResult(extracted)
    }

    fun validate(tile: MacroTile, raw: String): String? = read(tile, raw).error
}
