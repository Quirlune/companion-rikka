package dev.gray.companion.core

/** Supported CSS declarations compile once into the existing native style; no HTML runtime. */
object TileCss {
    private val hexColor = Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?")

    fun parse(source: String, base: MacroTileStyle = MacroTileStyle()): MacroTileStyle {
        var style = base
        source.split(';').map(String::trim).filter(String::isNotEmpty).forEach { declaration ->
            val separator = declaration.indexOf(':')
            require(separator > 0) { "样式须为 属性: 值;" }
            val property = declaration.substring(0, separator).trim().lowercase()
            val value = declaration.substring(separator + 1).trim()
            style = when (property) {
                "color" -> style.copy(foreground = nativeColor(value))
                "background-color" -> style.copy(background = nativeColor(value))
                "font-size" -> style.copy(fontSize = pixels(value, 8..72, "字号"))
                "border-radius" -> style.copy(cornerRadius = pixels(value, 0..64, "圆角"))
                "width" -> style.copy(widthPercent = when (value) {
                    "50%" -> 50
                    "100%" -> 100
                    else -> throw IllegalArgumentException("宽度支持 50% 或 100%。")
                })
                else -> throw IllegalArgumentException("不支持的样式属性：$property")
            }
        }
        return style
    }

    fun format(style: MacroTileStyle): String = listOf(
        "color: ${cssColor(style.foreground)};",
        "background-color: ${cssColor(style.background)};",
        "font-size: ${style.fontSize}px;",
        "border-radius: ${style.cornerRadius}px;",
        "width: ${style.widthPercent}%;",
    ).joinToString("\n")

    private fun pixels(value: String, range: IntRange, label: String): Int {
        require(value.endsWith("px")) { "$label 使用整数 px。" }
        val number = value.removeSuffix("px").trim().toIntOrNull()
        require(number != null && number in range) { "$label 范围为 ${range.first}–${range.last}px。" }
        return number
    }

    private fun nativeColor(value: String): String {
        if (value == "inherit") return ""
        if (value == "transparent") return "#00000000"
        require(hexColor.matches(value)) { "颜色支持 inherit、transparent、#RRGGBB 或 #RRGGBBAA。" }
        return if (value.length == 9) "#" + value.takeLast(2) + value.substring(1, 7) else value
    }

    private fun cssColor(value: String): String = when (value.length) {
        0 -> "inherit"
        9 -> "#" + value.substring(3) + value.substring(1, 3)
        else -> value
    }
}
