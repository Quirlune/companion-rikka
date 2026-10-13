package dev.gray.companion.ui

import android.graphics.Color as AndroidColor
import android.content.Context
import android.view.View
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.gray.companion.core.BodyDisplaySnapshot
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonPrimitive

@Composable
internal fun FrozenHtmlBody(snapshot: BodyDisplaySnapshot, messageId: String) {
    val html = snapshot.html ?: return
    val foreground = frozenBodyColor(snapshot.style.foreground) ?: MaterialTheme.colorScheme.onSurface
    val background = frozenBodyColor(snapshot.style.background) ?: Color.Transparent
    val initialText = remember(html) { org.jsoup.Jsoup.parse(html).wholeText() }
    InertHtmlView(html, foreground, background, snapshot.style.fontSize, messageId, initialBodyText = initialText)
}

@Composable
internal fun InertHtmlView(
    fragment: String, foreground: Color, background: Color, fontSize: Int, tileId: String,
    incremental: Boolean = false, incrementalShell: String? = null, incrementalTargetId: String = "companion-content",
    presentationKey: String? = null, presentationStreaming: Boolean = false,
    localImages: Map<String, File> = emptyMap(), localImageRoot: File? = null,
    initialBodyText: String? = null,
) {
    val density = LocalDensity.current
    val memory = LocalHtmlRenderMemory.current ?: remember { HtmlRenderMemory() }
    val shellFragment = if (incremental) incrementalShell.orEmpty() else fragment
    // Image authorization stays in the interceptor. No local paths enter the document.
    val document = remember(shellFragment, foreground, background, fontSize, incremental) {
        """<!doctype html><html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'sha256-$HEIGHT_SCRIPT_HASH'; style-src 'unsafe-inline'; img-src https://companion.invalid; font-src 'none'; connect-src 'none'; form-action 'none'; base-uri 'none'">
<style>html,body{margin:0;padding:0;background:${background.cssColor()};color:${foreground.cssColor()};font-family:sans-serif;font-size:${fontSize.coerceIn(8, 72)}px;overflow-wrap:anywhere}${if (incremental) "html,body{max-width:100%;overflow-x:hidden;overflow-anchor:none}#companion-content{display:flow-root;max-width:100%;min-width:0}" else ""}</style>
</head><body><div id="companion-content">$shellFragment</div><script>$HEIGHT_SCRIPT</script></body></html>"""
    }
    val documentDigest = remember(document, incrementalTargetId) {
        java.security.MessageDigest.getInstance("SHA-256")
            .digest((incrementalTargetId + "\u0000" + document).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val identity = remember(tileId, documentDigest, constraints.maxWidth, density.density, density.fontScale) {
            HtmlLayoutIdentity(tileId, documentDigest, constraints.maxWidth, density.density, density.fontScale)
        }
        // Cached geometry survives lazy disposal. A body-only hint reduces cold long-row
        // collapse; arbitrary author CSS still requires Chromium's actual measurement.
        var renderedHeight by remember(memory, identity) {
            mutableStateOf((memory.height(identity) ?: initialBodyText?.let {
                HtmlRenderMemory.estimateBodyHeight(it, identity.widthPx, density.density, density.fontScale, fontSize)
            } ?: 120f).dp)
        }
        var rendererFailed by remember(identity) { mutableStateOf(false) }
        if (rendererFailed) {
            Column(Modifier.fillMaxWidth().height(renderedHeight.coerceAtLeast(120.dp))) {
                Text("此段内容的显示进程已停止。", color = MaterialTheme.colorScheme.error)
                TextButton(onClick = { rendererFailed = false }) { Text("重新加载") }
            }
        } else {
            AndroidView(
                factory = { context -> HtmlViewHost(context) },
                modifier = Modifier.fillMaxWidth().height(renderedHeight),
                update = { host ->
                    host.onFailure = { rendererFailed = true }
                    if (host.released) {
                        rendererFailed = true
                    } else {
                        val view = host.webView
                        val rebound = view.onContentHeight == null
                        view.onContentHeight = {
                            memory.record(identity, it.value)
                            renderedHeight = it
                        }
                        view.localImages = localImages
                        view.localImageRoot = localImageRoot
                        view.settings.textZoom = (density.fontScale * 100).roundToInt().coerceIn(80, 300)
                        view.settings.loadsImagesAutomatically = localImages.isNotEmpty()
                        view.lockScrollToOrigin = renderedHeight < 16000.dp
                        view.isVerticalScrollBarEnabled = !view.lockScrollToOrigin
                        view.isHorizontalScrollBarEnabled = false
                        view.overScrollMode = if (view.lockScrollToOrigin) View.OVER_SCROLL_NEVER else view.defaultOverScrollMode
                        var state = view.documentState
                        if (state == null || state.identity != identity) {
                            state = HtmlDocumentState(identity, fragment, incremental,
                                incrementalTargetId, presentationKey, presentationStreaming)
                            // Invalidate the old page before any new navigation can deliver callbacks.
                            view.documentState = state
                            view.stopLoading()
                            view.scrollTo(0, 0)
                            view.loadDataWithBaseURL(state.url,
                                document.replaceFirst("<html>", "<html data-companion-navigation=\"${state.token}\">"),
                                "text/html", "UTF-8", null)
                        } else {
                            val samePresentation = state.presentationKey == presentationKey
                            val changed = state.fragment != fragment ||
                                state.presentationStreaming != presentationStreaming || !samePresentation
                            state.presentationKey = presentationKey
                            state.presentationStreaming = presentationStreaming
                            state.fragment = fragment
                            if (changed) state.revision++
                            if (state.ready) {
                                view.applyLatestFragment(state, animateGrowthAllowed = samePresentation)
                                if (rebound) {
                                    state.lastReport?.let { view.acceptHeight(it) }
                                    view.requestHeight()
                                }
                            }
                        }
                    }
                },
                // A deactivated lazy view can return to the same row. Preserve its document,
                // pending load and immutable local image permissions, but never its row callback.
                onReset = { host ->
                    host.onFailure = null
                    host.webView.onContentHeight = null
                    if (!host.released) {
                        host.webView.clearFocus()
                        host.webView.scrollTo(0, 0)
                    }
                },
                onRelease = { host -> host.release() },
            )
        }
    }
}

/** Owns removal before destroy, including renderer death while the row is deactivated. */
private class HtmlViewHost(context: Context) : FrameLayout(context) {
    var onFailure: (() -> Unit)? = null
    var released = false
        private set
    val webView = ContentSizedWebView(context)

    init {
        addView(webView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        webView.apply {
            setBackgroundColor(AndroidColor.TRANSPARENT)
            settings.javaScriptEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.domStorageEnabled = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.blockNetworkLoads = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportMultipleWindows(false)
            webChromeClient = object : WebChromeClient() {
                override fun onReceivedTitle(view: WebView, title: String?) {
                    HtmlHeightReport.parse(title)?.let { webView.acceptHeight(it) }
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) {
                    if (released) return
                    val state = webView.documentState ?: return
                    if (url != state.url) return
                    state.ready = true
                    webView.applyLatestFragment(state)
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    val notify = onFailure
                    runCatching {
                        me.rerere.rikkahub.utils.CrashHandler.recordWebViewExit(
                            context, detail.didCrash(), detail.rendererPriorityAtExit())
                    }
                    // This dead instance can never be reused. Every affected WebView owns
                    // this handler; returning true prevents the default app termination.
                    release(rendererGone = true)
                    notify?.invoke()
                    return true
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    if (request.isForMainFrame) return null
                    val file = webView.localImages[request.url.toString()]
                    val root = webView.localImageRoot
                    val safe = runCatching {
                        val canonicalRoot = root?.canonicalFile ?: return@runCatching null
                        val canonicalFile = file?.canonicalFile ?: return@runCatching null
                        val prefix = canonicalRoot.path + File.separator
                        canonicalFile.takeIf { canonicalRoot.isDirectory && it.isFile && it.path.startsWith(prefix) }
                    }.getOrNull()
                    if (safe != null) {
                        runCatching { FileInputStream(safe) }.getOrNull()?.let { stream ->
                            return WebResourceResponse(illustrationMimeType(safe), null, stream)
                        }
                    }
                    return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                }
            }
        }
    }

    fun release(rendererGone: Boolean = false) {
        if (released) return
        released = true
        onFailure = null
        webView.disposed = true
        webView.onContentHeight = null
        webView.documentState = null
        webView.localImages = emptyMap()
        webView.localImageRoot = null
        removeView(webView)
        if (!rendererGone) webView.stopLoading()
        webView.destroy()
    }
}

private class ContentSizedWebView(context: Context) : WebView(context) {
    var onContentHeight: ((Dp) -> Unit)? = null
    var documentState: HtmlDocumentState? = null
    var disposed = false
    @Volatile var localImages: Map<String, File> = emptyMap()
    @Volatile var localImageRoot: File? = null
    val defaultOverScrollMode = overScrollMode
    var lockScrollToOrigin = false
        set(value) {
            field = value
            if (!disposed && value && (scrollX != 0 || scrollY != 0)) super.scrollTo(0, 0)
        }

    fun acceptHeight(report: HtmlHeightReport) {
        if (disposed) return
        val state = documentState ?: return
        val pageScale = scale
        if (!state.ready || pageScale <= 0 || !pageScale.isFinite() ||
            width != state.identity.widthPx ||
            !report.matches(state.token, state.revision, width / pageScale)) return
        state.lastReport = report
        onContentHeight?.invoke(
            (report.heightCss * pageScale / state.identity.density).dp.coerceIn(80.dp, 16000.dp))
    }

    fun requestHeight() {
        if (disposed) return
        val state = documentState?.takeIf { it.ready } ?: return
        evaluateJavascript(
            "(function(){if(document.documentElement.dataset.companionNavigation==='${state.token}'" +
                "&&window.__companionReportHeight)window.__companionReportHeight();})();", null)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) requestHeight()
    }

    override fun scrollTo(x: Int, y: Int) {
        if (disposed) return
        if (lockScrollToOrigin) super.scrollTo(0, 0) else super.scrollTo(x, y)
    }

    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
        if (disposed) return
        if (lockScrollToOrigin) super.onOverScrolled(0, 0, clampedX, clampedY)
        else super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (!disposed && lockScrollToOrigin && (l != 0 || t != 0)) super.scrollTo(0, 0)
    }
}

// Measure the wrapper, not scrollHeight (which cannot shrink below the previous viewport).
// No measurement is published until native applies the intended DOM revision.
private const val HEIGHT_SCRIPT = """(function(){
var root=document.getElementById('companion-content');if(!root)return;
window.__companionHeightRevision=0;
var reportSerial=0;
window.__companionReportHeight=function(){
 var revision=window.__companionHeightRevision;if(!revision)return;
 var token=document.documentElement.dataset.companionNavigation;if(!token)return;
 document.title='companion-height:'+token+':'+revision+':'+window.innerWidth+':'+Math.ceil(root.getBoundingClientRect().height)+':'+(++reportSerial);
};
new ResizeObserver(window.__companionReportHeight).observe(root);
window.addEventListener('resize',window.__companionReportHeight);
})();"""
private val HEIGHT_SCRIPT_HASH = android.util.Base64.encodeToString(
    java.security.MessageDigest.getInstance("SHA-256").digest(HEIGHT_SCRIPT.toByteArray(Charsets.UTF_8)),
    android.util.Base64.NO_WRAP)

private fun Color.cssColor(): String {
    val argb = toArgb()
    return "#%06X%02X".format(argb and 0xFFFFFF, argb ushr 24)
}

private fun frozenBodyColor(value: String): Color? = if (Regex("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?").matches(value)) runCatching { Color(AndroidColor.parseColor(value)) }.getOrNull() else null

private fun illustrationMimeType(file: File): String = when (file.extension.lowercase()) {
    "png" -> "image/png"
    "webp" -> "image/webp"
    "jpg", "jpeg" -> "image/jpeg"
    else -> "application/octet-stream"
}

private class HtmlDocumentState(
    val identity: HtmlLayoutIdentity,
    var fragment: String,
    val incremental: Boolean,
    val targetId: String,
    var presentationKey: String?,
    var presentationStreaming: Boolean,
) {
    val token = java.util.UUID.randomUUID().toString()
    val url = "https://companion.invalid/html-tile/$token/"
    var ready = false
    var revision = 1L
    var appliedRevision = 0L
    var applied: String? = null
    var appliedStreaming = false
    var lastReport: HtmlHeightReport? = null
}

private fun ContentSizedWebView.applyLatestFragment(state: HtmlDocumentState, animateGrowthAllowed: Boolean = false) {
    if (disposed || documentState !== state || state.appliedRevision == state.revision) return
    val animateGrowth = animateGrowthAllowed && state.applied != null &&
        state.appliedStreaming && state.presentationStreaming
    val replace = if (state.incremental)
        BodyAppearanceScripts.replaceFragment(state.fragment, state.targetId, animateGrowth) else ""
    val quotedTarget = JsonPrimitive(if (state.incremental) state.targetId else "companion-content").toString()
    // The guard is executed in Chromium, so a queued script from an earlier navigation
    // cannot mutate its replacement document. Version publication follows the DOM write.
    evaluateJavascript("""(function(){
if(document.documentElement.dataset.companionNavigation!=='${state.token}')return;
if(!document.getElementById($quotedTarget)||!window.__companionReportHeight)return;
$replace
window.__companionHeightRevision=${state.revision};
window.__companionReportHeight();
})();""", null)
    state.applied = state.fragment
    state.appliedStreaming = state.presentationStreaming
    state.appliedRevision = state.revision
}
internal object BodyAppearanceScripts {
    fun replaceFragment(fragment: String, targetId: String = "companion-content", animateGrowth: Boolean = false): String {
        // JSON string encoding prevents content from becoming executable JavaScript.
        val quoted = JsonPrimitive(fragment).toString()
            .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        val quotedId = JsonPrimitive(targetId).toString()
            .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")
        return """(function(){
var root=document.getElementById($quotedId);if(!root)return;
var incoming=document.createElement('div');incoming.innerHTML=$quoted;
function clearFades(target){
  Array.from(target.querySelectorAll('[data-companion-stream-fade]')).forEach(function(span){
    var parent=span.parentNode;if(!parent)return;
    parent.replaceChild(document.createTextNode(span.textContent||''),span);parent.normalize();
  });
}
// Preserve unchanged paragraphs and text nodes while Markdown's unfinished tail evolves.
function sync(target,source){
  for(var i=0;i<source.childNodes.length;i++){
    var next=source.childNodes[i],current=target.childNodes[i];
    if(!current){target.appendChild(next.cloneNode(true));continue;}
    if(current.isEqualNode(next))continue;
    if(current.nodeType!==next.nodeType||current.nodeName!==next.nodeName){
      target.replaceChild(next.cloneNode(true),current);continue;
    }
    if(current.nodeType===3){current.nodeValue=next.nodeValue;continue;}
    if(current.nodeType!==1){target.replaceChild(next.cloneNode(true),current);continue;}
    Array.from(current.attributes).forEach(function(attr){
      if(!next.hasAttribute(attr.name))current.removeAttribute(attr.name);
    });
    Array.from(next.attributes).forEach(function(attr){
      if(current.getAttribute(attr.name)!==attr.value)current.setAttribute(attr.name,attr.value);
    });
    sync(current,next);
  }
  while(target.childNodes.length>source.childNodes.length)target.removeChild(target.lastChild);
}
clearFades(root);
var previousText=root.textContent||'',nextText=incoming.textContent||'';
var appendStart=previousText.length;
var splitSurrogate=appendStart>0&&appendStart<nextText.length&&
  previousText.charCodeAt(appendStart-1)>=0xD800&&previousText.charCodeAt(appendStart-1)<=0xDBFF&&
  nextText.charCodeAt(appendStart)>=0xDC00&&nextText.charCodeAt(appendStart)<=0xDFFF;
var shouldFade=$animateGrowth&&nextText.length>appendStart&&nextText.indexOf(previousText)===0&&!splitSurrogate;
sync(root,incoming);
if(shouldFade){
  var walker=document.createTreeWalker(root,NodeFilter.SHOW_TEXT),nodes=[],node;
  while((node=walker.nextNode()))nodes.push(node);
  var offset=0;
  nodes.forEach(function(textNode){
    var value=textNode.nodeValue||'',start=offset;offset+=value.length;
    var localStart=Math.max(0,appendStart-start);if(localStart>=value.length)return;
    var suffix=value.slice(localStart),parent=textNode.parentNode;if(!suffix||!parent)return;
    var fade=document.createElement('span');fade.setAttribute('data-companion-stream-fade','true');
    fade.style.opacity='0';fade.style.transition='opacity 140ms ease-out';fade.textContent=suffix;
    if(localStart>0){textNode.nodeValue=value.slice(0,localStart);parent.insertBefore(fade,textNode.nextSibling);}
    else parent.replaceChild(fade,textNode);
    requestAnimationFrame(function(){requestAnimationFrame(function(){fade.style.opacity='1';});});
  });
}
})();"""
    }
}
