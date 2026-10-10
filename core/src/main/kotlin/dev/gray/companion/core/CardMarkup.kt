package dev.gray.companion.core

/** Generated markup only. CSS cannot load resources or terminate its style element. */
object CardMarkup {
    /** Readable ASCII path IDs; inference disambiguates collisions in sample field order. */
    fun defaultElementId(key: String): String {
        val readable = key.replace(".", "__")
        return if (Regex("[A-Za-z_][A-Za-z0-9_-]{0,119}").matches(readable)) readable
        else "field_" + java.security.MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8)).take(12).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun validate(css: String): String? {
        if (css.length > 65536) return "卡片 CSS 过长"
        // Reject escapes and inspect comment-free CSS to prevent obfuscated resource loads.
        val inspected = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(css, "")
        if (css.any { it == '<' || it == '\\' || it == '\u0000' } ||
            Regex("(?i)@import|@font-face|url\\s*\\(|image-set\\s*\\(|expression\\s*\\(|-moz-binding|behavior\\s*:|(?:https?|javascript|data):").containsMatchIn(inspected))
            return "CSS 仅支持静态样式，不能包含标签、转义或外部资源"
        return null
    }

    fun render(snapshot: CardSnapshot, compact: Boolean = false): String {
        require(validate(snapshot.css) == null) { "卡片 CSS 无效" }
        val fields = snapshot.fields.associateBy { it.key }
        val elements = snapshot.elements.ifEmpty { snapshot.fields.map { CardElement(it.key, it.key) } }
        val compactCss = buildString {
            elements.forEachIndexed { index, element ->
                val field = fields[element.fieldKey] ?: return@forEachIndexed
                if (field.kind == CardFieldKind.LIST && field.items.size > field.compactLimit) {
                    append(".card .element-$index .field-list li:nth-child(n+${field.compactLimit + 1}){display:none}")
                    append(".card .element-$index .field-more{display:block}")
                }
            }
        }
        return buildString {
            append("<style>")
            append(".card{box-sizing:border-box;padding:12px;color:inherit;font:14px sans-serif;overflow-wrap:anywhere}.card-title{font-weight:600;margin-bottom:10px}.card-element{margin:8px 0}.field-title{opacity:.65;font-size:.85em}.field-value{white-space:pre-wrap}.field-list{padding-left:20px;margin:4px 0}.field-progress{width:100%}.field-more{display:none;opacity:.6;font-size:.85em}")
            if (snapshot.autoAdapt) append(".card{max-width:100%}.card-element{min-width:0}")
            if (compact || snapshot.presentation == CardPresentation.STRIP) append(compactCss)
            else if (snapshot.autoAdapt) {
                append("@media(max-width:${snapshot.minimumWidthDp}px){")
                append(compactCss); append("}")
            }
            append(snapshot.css)
            append("</style><section class=\"card\"><header class=\"card-title\">")
            append(escape(snapshot.title)); append("</header>")
            fun renderElement(index: Int, element: CardElement) {
                val field = fields[element.fieldKey] ?: return
                append("<div class=\"card-element element-$index\" id=\""); append(escape(element.id)); append("\">")
                append("<div class=\"field-title\">"); append(escape(element.title.ifBlank { field.title })); append("</div>")
                if (field.kind == CardFieldKind.LIST) {
                    append("<ul class=\"field-list\">")
                    field.items.forEach { append("<li>"); append(escape(it)); append("</li>") }
                    append("</ul>")
                    if (field.items.size > field.compactLimit) {
                        append("<div class=\"field-more\">另有 ")
                        append(field.items.size - field.compactLimit); append(" 项</div>")
                    }
                } else {
                    append("<div class=\"field-value\">"); append(escape(field.value)); append("</div>")
                    if (field.kind == CardFieldKind.PROGRESS && field.value.toDoubleOrNull() != null) {
                        val fraction = ((field.value.toDouble() - field.minimum) / (field.maximum - field.minimum)).coerceIn(0.0, 1.0)
                        append("<progress class=\"field-progress\" max=\"1\" value=\""); append(fraction); append("\"></progress>")
                    }
                }
                append("</div>")
            }
            elements.forEachIndexed { index, element -> if (element.sectionId.isEmpty()) renderElement(index, element) }
            snapshot.sections.forEach { section ->
                append("<details class=\"card-section\" id=\""); append(escape(section.id)); append('"')
                if (section.initiallyExpanded) append(" open")
                append("><summary class=\"card-section-title\">"); append(escape(section.title))
                append("</summary><div class=\"card-section-content\">")
                elements.forEachIndexed { index, element -> if (element.sectionId == section.id) renderElement(index, element) }
                append("</div></details>")
            }
            append("</section>")
        }
    }

    private fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;")
        .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
