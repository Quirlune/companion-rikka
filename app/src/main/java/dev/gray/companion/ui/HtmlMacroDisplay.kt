package dev.gray.companion.ui

import android.graphics.Color as AndroidColor
import android.webkit.WebChromeClient
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.gray.companion.core.BodyDisplaySnapshot
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonPrimitive

@Composable
internal fun FrozenHtmlBody(snapshot: BodyDisplaySnapshot, messageId: String) {
    val html = snapshot.html ?: return
    val foreground = frozenBodyColor(snapshot.style.foreground) ?: MaterialTheme.colorScheme.onSurface
    val background = frozenBodyColor(snapshot.style.background) ?: Color.Transparent
    InertHtmlView(html, foreground, background, snapshot.style.fontSize, messageId)
}

@Composable
internal fun InertHtmlView(fragment: String, foreground: Color, background: Color, fontSize: Int, tileId: String, incremental: Boolean = false) {
    val fontScale = LocalDensity.current.fontScale
    var renderedHeight by remember(tileId) { mutableStateOf(120.dp) }
    val shellFragment = if (incremental) "" else fragment
    val document = remember(shellFragment, foreground, background, fontSize, incremental) {
        """<!doctype html><html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'sha256-$HEIGHT_SCRIPT_HASH'; style-src 'unsafe-inline'; img-src 'none'; font-src 'none'; connect-src 'none'; form-action 'none'; base-uri 'none'">
<style>html,body{margin:0;padding:0;background:${background.cssColor()};color:${foreground.cssColor()};font-family:sans-serif;font-size:${fontSize.coerceIn(8, 72)}px;overflow-wrap:anywhere}${if (incremental) "html,body{max-width:100%;overflow-x:hidden}#companion-content{display:flow-root;max-width:100%;min-width:0}" else ""}</style>
</head><body><div id="companion-content">$shellFragment</div><script>$HEIGHT_SCRIPT</script></body></html>"""
    }
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(AndroidColor.TRANSPARENT)
                // CSP permits only our hashed size observer; author scripts and network remain blocked.
                settings.javaScriptEnabled = true
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
                webChromeClient = object : WebChromeClient() {
                    override fun onReceivedTitle(view: WebView, title: String?) {
                        val cssHeight = title?.removePrefix("companion-height:")?.toFloatOrNull() ?: return
                        if (!cssHeight.isFinite() || cssHeight < 0) return
                        val density = view.resources.displayMetrics.density
                        if (density > 0) renderedHeight = (cssHeight * view.scale / density).dp.coerceIn(80.dp, 16000.dp)
                    }
                }
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        val state = view.tag as? IncrementalHtmlState ?: return
                        if (url != state.url) return
                        state.ready = true
                        view.applyLatestFragment(state)
                    }
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        if (request.isForMainFrame) null
                        else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }
            }
        },
        modifier = Modifier.fillMaxWidth().height(renderedHeight),
        update = { view ->
            view.settings.textZoom = (fontScale * 100).roundToInt().coerceIn(80, 300)
            if (incremental) {
                var state = view.tag as? IncrementalHtmlState
                if (state == null || state.document != document) {
                    state = IncrementalHtmlState(document, fragment)
                    view.tag = state
                    view.loadDataWithBaseURL(state.url, document, "text/html", "UTF-8", null)
                } else {
                    state.fragment = fragment
                    if (state.ready) view.applyLatestFragment(state)
                }
            } else if (view.tag != document) {
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

// Measure a content wrapper, not document scrollHeight, whose minimum is the previous viewport height.
private const val HEIGHT_SCRIPT = "new ResizeObserver(function(){document.title='companion-height:'+Math.ceil(document.getElementById('companion-content').getBoundingClientRect().height)}).observe(document.getElementById('companion-content'));"
private val HEIGHT_SCRIPT_HASH = android.util.Base64.encodeToString(
    java.security.MessageDigest.getInstance("SHA-256").digest(HEIGHT_SCRIPT.toByteArray(Charsets.UTF_8)),
    android.util.Base64.NO_WRAP)

private fun Color.cssColor(): String {
    val argb = toArgb()
    return "#%06X%02X".format(argb and 0xFFFFFF, argb ushr 24)
}

private fun frozenBodyColor(value: String): Color? = if (Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?").matches(value)) runCatching { Color(AndroidColor.parseColor(value)) }.getOrNull() else null

// The latest fragment remains queued while the initial document loads. Navigation tokens
// prevent a stale page-finished callback from applying content to a replacement shell.
private class IncrementalHtmlState(val document: String, var fragment: String) {
    val url = "https://companion.invalid/styled-body/${java.util.UUID.randomUUID()}/"
    var ready = false
    var applied: String? = null
}

private fun WebView.applyLatestFragment(state: IncrementalHtmlState) {
    if (state.applied == state.fragment) return
    val fragment = state.fragment
    evaluateJavascript(BodyAppearanceScripts.replaceFragment(fragment), null)
    state.applied = fragment
}

internal object BodyAppearanceScripts {
    fun replaceFragment(fragment: String): String {
        // JSON string encoding prevents content from becoming executable JavaScript.
        val quoted = JsonPrimitive(fragment).toString()
            .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        return "(function(){var root=document.getElementById('companion-content');if(root)root.innerHTML=" + quoted + ";})();"
    }
}
