package dev.gray.companion.ui

import android.graphics.Color as AndroidColor
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.gray.companion.core.BodyDisplaySnapshot
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt

/** Inert local document for the currently visible tile only. Macro values are escaped in core. */
@Composable
internal fun HtmlMacroDisplay(item: ResolvedTile) {
    val tile = item.tile
    val error = item.error
    val html = item.html
    val foreground = tileColor(tile.style.foreground) ?: MaterialTheme.colorScheme.onSurface
    val background = tileColor(tile.style.background) ?: MaterialTheme.colorScheme.surfaceContainerLow
    Surface(shape = RoundedCornerShape(tile.style.cornerRadius.dp), color = background, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(tile.title, style = MaterialTheme.typography.titleSmall, color = foreground)
            when {
                error != null -> Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                item.waiting -> Text("等待首次生成", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                html != null -> InertHtmlView(html, foreground, background, tile.style.fontSize, tile.id)
                else -> Text("尚无可显示的内容", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun FrozenHtmlBody(snapshot: BodyDisplaySnapshot, messageId: String) {
    val html = snapshot.html ?: return
    val foreground = tileColor(snapshot.style.foreground) ?: MaterialTheme.colorScheme.onSurface
    val background = tileColor(snapshot.style.background) ?: Color.Transparent
    InertHtmlView(html, foreground, background, snapshot.style.fontSize, messageId)
}

@Composable
internal fun InertHtmlView(fragment: String, foreground: Color, background: Color, fontSize: Int, tileId: String) {
    val fontScale = LocalDensity.current.fontScale
    var height by remember(tileId) { mutableStateOf(120.dp) }
    val document = remember(fragment, foreground, background, fontSize) {
        """<!doctype html><html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src 'none'; font-src 'none'; connect-src 'none'; form-action 'none'; base-uri 'none'">
<style>html,body{margin:0;padding:0;background:${background.cssColor()};color:${foreground.cssColor()};font-family:sans-serif;font-size:${fontSize.coerceIn(8, 72)}px;overflow-wrap:anywhere}</style>
</head><body>$fragment</body></html>"""
    }
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                settings.javaScriptEnabled = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.allowFileAccessFromFileURLs = false
                settings.allowUniversalAccessFromFileURLs = false
                settings.blockNetworkLoads = true
                settings.loadsImagesAutomatically = false
                settings.cacheMode = WebSettings.LOAD_NO_CACHE
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                settings.setSupportMultipleWindows(false)
                settings.textZoom = (fontScale * 100).roundToInt().coerceIn(80, 300)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        if (request.isForMainFrame) null
                        else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                    override fun onPageFinished(view: WebView, url: String?) {
                        view.post { height = contentHeightDp(view) }
                    }
                }
            }
        },
        modifier = Modifier.fillMaxWidth().height(height),
        update = { view ->
            view.settings.textZoom = (fontScale * 100).roundToInt().coerceIn(80, 300)
            if (view.tag != document) {
                view.tag = document
                view.loadDataWithBaseURL("https://companion.invalid/html-tile/", document, "text/html", "UTF-8", null)
            }
        },
        onReset = null,
        onRelease = { view ->
            view.stopLoading()
            view.loadUrl("about:blank")
            view.removeAllViews()
            view.destroy()
        },
    )
}

private fun contentHeightDp(view: WebView): Dp {
    val density = view.resources.displayMetrics.density
    val pixels = view.contentHeight * view.scale
    return if (density > 0f && pixels.isFinite() && pixels > 0f) (pixels / density).dp.coerceIn(80.dp, 360.dp) else 120.dp
}

private fun Color.cssColor(): String {
    val argb = toArgb()
    return "#%06X%02X".format(argb and 0xFFFFFF, argb ushr 24)
}
