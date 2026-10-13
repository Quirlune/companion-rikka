package dev.gray.companion.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Distinct navigation subjects with one 24 dp canvas and one rounded 1.8 dp stroke. */
internal object CompanionIcons {
    private fun icon(name: String, drawing: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp,
            viewportWidth = 24f, viewportHeight = 24f).apply {
            path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round) { drawing() }
        }.build()

    private fun PathBuilder.circle(x: Float, y: Float, r: Float) {
        val c = r * .5522848f
        moveTo(x + r, y)
        curveTo(x + r, y + c, x + c, y + r, x, y + r)
        curveTo(x - c, y + r, x - r, y + c, x - r, y)
        curveTo(x - r, y - c, x - c, y - r, x, y - r)
        curveTo(x + c, y - r, x + r, y - c, x + r, y)
        close()
    }

    val Session by lazy { icon("Session") {
        moveTo(5f, 5f); lineTo(19f, 5f); lineTo(19f, 16f); lineTo(12f, 16f); lineTo(8f, 20f); lineTo(8f, 16f); lineTo(5f, 16f); close()
        moveTo(8f, 9f); lineTo(16f, 9f); moveTo(8f, 12f); lineTo(13f, 12f)
    } }
    val MainCall by lazy { icon("MainCall") {
        circle(12f, 12f, 3f); circle(5f, 5f, 1.5f); circle(19f, 5f, 1.5f); circle(19f, 19f, 1.5f)
        moveTo(7f, 7f); lineTo(10f, 10f); moveTo(17f, 7f); lineTo(14f, 10f); moveTo(17f, 17f); lineTo(14f, 14f)
    } }
    val PromptPackage by lazy { icon("PromptPackage") {
        moveTo(12f, 6f); curveTo(9f, 4f, 6f, 4f, 3f, 5f); lineTo(3f, 18f); curveTo(6f, 17f, 9f, 17f, 12f, 19f)
        curveTo(15f, 17f, 18f, 17f, 21f, 18f); lineTo(21f, 5f); curveTo(18f, 4f, 15f, 4f, 12f, 6f); lineTo(12f, 19f)
        moveTo(6f, 9f); lineTo(9f, 9f); moveTo(15f, 9f); lineTo(18f, 9f)
    } }
    val CallGroup by lazy { icon("CallGroup") {
        moveTo(10f, 3f); lineTo(14f, 3f); lineTo(14f, 7f); lineTo(10f, 7f); close()
        moveTo(3f, 17f); lineTo(8f, 17f); lineTo(8f, 21f); lineTo(3f, 21f); close()
        moveTo(16f, 17f); lineTo(21f, 17f); lineTo(21f, 21f); lineTo(16f, 21f); close()
        moveTo(12f, 7f); lineTo(12f, 12f); moveTo(5.5f, 12f); lineTo(18.5f, 12f)
        moveTo(5.5f, 12f); lineTo(5.5f, 17f); moveTo(18.5f, 12f); lineTo(18.5f, 17f)
    } }
    val Extensions by lazy { icon("Extensions") {
        moveTo(4f, 4f); lineTo(9f, 4f); curveTo(9f, 2f, 12f, 2f, 12f, 4f); curveTo(12f, 6f, 9f, 6f, 9f, 4f)
        lineTo(14f, 4f); lineTo(14f, 9f); curveTo(16f, 9f, 16f, 12f, 14f, 12f)
        curveTo(12f, 12f, 12f, 9f, 14f, 9f); lineTo(14f, 20f); lineTo(4f, 20f); close()
        moveTo(4f, 14f); curveTo(2f, 14f, 2f, 17f, 4f, 17f)
    } }
    val Agent by lazy { icon("Agent") {
        moveTo(6f, 8f); curveTo(6f, 6f, 8f, 5f, 10f, 5f); lineTo(14f, 5f); curveTo(16f, 5f, 18f, 6f, 18f, 8f)
        lineTo(18f, 17f); curveTo(18f, 19f, 16f, 20f, 14f, 20f); lineTo(10f, 20f); curveTo(8f, 20f, 6f, 19f, 6f, 17f); close()
        moveTo(12f, 5f); lineTo(12f, 2f); circle(12f, 2f, .7f)
        circle(9f, 12f, 1f); circle(15f, 12f, 1f); moveTo(10f, 16f); lineTo(14f, 16f)
    } }
    val Workspace by lazy { icon("Workspace") {
        moveTo(3f, 7f); lineTo(9f, 7f); lineTo(11f, 9f); lineTo(21f, 9f); lineTo(21f, 19f); lineTo(3f, 19f); close()
        moveTo(3f, 7f); lineTo(3f, 5f); lineTo(9f, 5f); lineTo(11f, 7f)
        moveTo(8f, 13f); lineTo(16f, 13f); moveTo(8f, 16f); lineTo(13f, 16f)
    } }
    val Models by lazy { icon("Models") {
        moveTo(4f, 4f); lineTo(20f, 4f); lineTo(20f, 10f); lineTo(4f, 10f); close()
        moveTo(4f, 14f); lineTo(20f, 14f); lineTo(20f, 20f); lineTo(4f, 20f); close()
        circle(7f, 7f, .7f); circle(7f, 17f, .7f)
        moveTo(11f, 7f); lineTo(17f, 7f); moveTo(11f, 17f); lineTo(17f, 17f)
    } }
    val ChatDisplay by lazy { icon("ChatDisplay") {
        moveTo(4f, 4f); lineTo(15f, 4f); lineTo(15f, 13f); lineTo(10f, 13f); lineTo(7f, 16f); lineTo(7f, 13f); lineTo(4f, 13f); close()
        moveTo(17f, 8f); lineTo(20f, 8f); lineTo(20f, 17f); lineTo(17f, 17f); lineTo(17f, 20f); lineTo(14f, 17f); lineTo(11f, 17f)
        moveTo(7f, 8f); lineTo(12f, 8f)
    } }
    val Logs by lazy { icon("Logs") {
        moveTo(6f, 3f); lineTo(16f, 3f); lineTo(20f, 7f); lineTo(20f, 21f); lineTo(6f, 21f); close()
        moveTo(16f, 3f); lineTo(16f, 7f); lineTo(20f, 7f)
        circle(10f, 11f, .7f); circle(10f, 15f, .7f)
        moveTo(13f, 11f); lineTo(17f, 11f); moveTo(13f, 15f); lineTo(17f, 15f)
    } }
    val Import by lazy { icon("Import") {
        moveTo(12f, 3f); lineTo(12f, 14f); moveTo(8f, 10f); lineTo(12f, 14f); lineTo(16f, 10f)
        moveTo(4f, 16f); lineTo(4f, 20f); lineTo(20f, 20f); lineTo(20f, 16f)
    } }
    val Undo by lazy { icon("Undo") {
        moveTo(9f, 6f); lineTo(4f, 11f); lineTo(9f, 16f)
        moveTo(4f, 11f); lineTo(14f, 11f); curveTo(18f, 11f, 20f, 13f, 20f, 17f)
    } }
    val Export by lazy { icon("Export") {
        moveTo(12f, 15f); lineTo(12f, 4f); moveTo(8f, 8f); lineTo(12f, 4f); lineTo(16f, 8f)
        moveTo(4f, 16f); lineTo(4f, 20f); lineTo(20f, 20f); lineTo(20f, 16f)
    } }
    val Reasoning by lazy { icon("Reasoning") {
        circle(5f, 5f, 2f); circle(19f, 5f, 2f); circle(12f, 19f, 2f)
        moveTo(7f, 6f); lineTo(11f, 11f); lineTo(11f, 17f)
        moveTo(17f, 6f); lineTo(13f, 11f); lineTo(13f, 17f)
        moveTo(11f, 11f); lineTo(13f, 11f)
    } }
    val QuickInput by lazy { icon("QuickInput") {
        moveTo(3f, 6f); lineTo(21f, 6f); lineTo(21f, 18f); lineTo(3f, 18f); close()
        moveTo(6f, 9f); lineTo(6.5f, 9f); moveTo(10f, 9f); lineTo(10.5f, 9f)
        moveTo(14f, 9f); lineTo(14.5f, 9f); moveTo(18f, 9f); lineTo(18.5f, 9f)
        moveTo(6f, 12f); lineTo(6.5f, 12f); moveTo(10f, 12f); lineTo(10.5f, 12f)
        moveTo(14f, 12f); lineTo(14.5f, 12f); moveTo(18f, 12f); lineTo(18.5f, 12f)
        moveTo(8f, 15f); lineTo(16f, 15f)
    } }
    val Options by lazy { icon("Options") {
        moveTo(4f, 7f); lineTo(6f, 9f); lineTo(9f, 5f)
        moveTo(12f, 7f); lineTo(20f, 7f)
        moveTo(4f, 16f); lineTo(6f, 18f); lineTo(9f, 14f)
        moveTo(12f, 16f); lineTo(20f, 16f)
    } }
    val Preferences by lazy { icon("Preferences") {
        moveTo(4f, 7f); lineTo(20f, 7f); moveTo(4f, 12f); lineTo(20f, 12f); moveTo(4f, 17f); lineTo(20f, 17f)
        circle(9f, 7f, 1.5f); circle(16f, 12f, 1.5f); circle(11f, 17f, 1.5f)
    } }
    val CardLayout by lazy { icon("CardLayout") {
        moveTo(3f, 4f); lineTo(21f, 4f); lineTo(21f, 20f); lineTo(3f, 20f); close()
        moveTo(3f, 10f); lineTo(21f, 10f); moveTo(11f, 10f); lineTo(11f, 20f)
        moveTo(6f, 7f); lineTo(10f, 7f)
    } }
    val Display by lazy { icon("Display") {
        moveTo(3f, 4f); lineTo(21f, 4f); lineTo(21f, 17f); lineTo(3f, 17f); close()
        moveTo(12f, 17f); lineTo(12f, 21f); moveTo(8f, 21f); lineTo(16f, 21f)
    } }
    val Theme by lazy { icon("Theme") {
        circle(12f, 12f, 4f)
        moveTo(12f, 2f); lineTo(12f, 5f); moveTo(12f, 19f); lineTo(12f, 22f)
        moveTo(2f, 12f); lineTo(5f, 12f); moveTo(19f, 12f); lineTo(22f, 12f)
        moveTo(5f, 5f); lineTo(7f, 7f); moveTo(17f, 17f); lineTo(19f, 19f)
        moveTo(19f, 5f); lineTo(17f, 7f); moveTo(7f, 17f); lineTo(5f, 19f)
    } }
    val Network by lazy { icon("Network") {
        circle(12f, 12f, 9f)
        moveTo(3f, 12f); lineTo(21f, 12f)
        moveTo(12f, 3f); curveTo(8f, 7f, 8f, 17f, 12f, 21f)
        moveTo(12f, 3f); curveTo(16f, 7f, 16f, 17f, 12f, 21f)
        moveTo(5f, 7f); lineTo(19f, 7f); moveTo(5f, 17f); lineTo(19f, 17f)
    } }
}
