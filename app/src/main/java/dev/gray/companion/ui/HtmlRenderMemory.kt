package dev.gray.companion.ui

import androidx.compose.runtime.staticCompositionLocalOf
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Page-owned geometry only. Never retain a View, Context or message body here. */
internal class HtmlRenderMemory(private val capacity: Int = 256) {
    private val heights = LinkedHashMap<HtmlLayoutIdentity, Float>(16, 0.75f, true)

    fun height(identity: HtmlLayoutIdentity): Float? = heights[identity]

    fun record(identity: HtmlLayoutIdentity, heightDp: Float) {
        if (!heightDp.isFinite() || heightDp < 0) return
        heights[identity] = heightDp.coerceIn(80f, 16000f)
        while (heights.size > capacity.coerceAtLeast(1)) {
            heights.remove(heights.keys.first())
        }
    }

    companion object {
        /** Cold-layout hint only; author CSS and Markdown structure can change the true size. */
        fun estimateBodyHeight(text: String, widthPx: Int, density: Float, fontScale: Float, fontSize: Int): Float {
            if (widthPx <= 0 || !density.isFinite() || density <= 0 || !fontScale.isFinite()) return 120f
            val zoom = (fontScale * 100).roundToInt().coerceIn(80, 300) / 100f
            val em = fontSize.coerceIn(8, 72) * zoom
            val lineWidth = (widthPx / density - 32f).coerceAtLeast(em)
            val lineHeight = em * 1.75f
            var lines = 0
            var paragraphWidth = 0f
            var index = 0
            while (index < text.length) {
                val codePoint = Character.codePointAt(text, index)
                index += Character.charCount(codePoint)
                if (codePoint == '\n'.code) {
                    lines += ceil(paragraphWidth / lineWidth).toInt().coerceAtLeast(1)
                    paragraphWidth = 0f
                } else if (codePoint != '\r'.code) {
                    paragraphWidth += em * if (codePoint < 128) 0.55f else 1f
                }
                if (lines * lineHeight + paragraphWidth / lineWidth * lineHeight >= 8000f) return 8000f
            }
            lines += ceil(paragraphWidth / lineWidth).toInt().coerceAtLeast(1)
            // Reserve a little template padding, but do not force a short body to a full screen.
            return (lines * lineHeight + 32f).coerceIn(120f, 8000f)
        }
    }
}

internal val LocalHtmlRenderMemory = staticCompositionLocalOf<HtmlRenderMemory?> { null }

internal data class HtmlLayoutIdentity(
    val tileId: String,
    val documentDigest: String,
    val widthPx: Int,
    val density: Float,
    val fontScale: Float,
)

/** Every title must identify both the navigation and the DOM revision it measured. */
internal data class HtmlHeightReport(
    val token: String,
    val revision: Long,
    val widthCss: Float,
    val heightCss: Float,
) {
    fun matches(token: String, revision: Long, expectedWidthCss: Float): Boolean =
        this.token == token && this.revision == revision && revision > 0 &&
            expectedWidthCss.isFinite() && expectedWidthCss > 0 &&
            abs(widthCss - expectedWidthCss) <= 1.5f

    companion object {
        fun parse(title: String?): HtmlHeightReport? {
            if (title == null || !title.startsWith("companion-height:")) return null
            val parts = title.removePrefix("companion-height:").split(':')
            // Serial makes repeated same-size reports observable after a view rebind.
            if (parts.size != 5 || parts[4].toLongOrNull() == null) return null
            val revision = parts[1].toLongOrNull() ?: return null
            val width = parts[2].toFloatOrNull() ?: return null
            val height = parts[3].toFloatOrNull() ?: return null
            if (parts[0].isEmpty() || revision <= 0 || !width.isFinite() || width <= 0 ||
                !height.isFinite() || height < 0) return null
            return HtmlHeightReport(parts[0], revision, width, height)
        }
    }
}
