package dev.gray.companion.ui

import android.graphics.Color as AndroidColor
import android.content.Context
import android.view.View
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
import androidx.compose.runtime.rememberUpdatedState
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
internal fun InertHtmlView(
    fragment: String, foreground: Color, background: Color, fontSize: Int, tileId: String,
    incremental: Boolean = false, incrementalShell: String? = null, incrementalTargetId: String = "companion-content",
    presentationKey: String? = null, presentationStreaming: Boolean = false,
) {
    val fontScale = LocalDensity.current.fontScale
    var renderedHeight by remember(tileId) { mutableStateOf(120.dp) }
    // AndroidView can survive a candidate/tile change; its factory callback must write
    // the current tile's height state instead of the state captured on first creation.
    val updateHeight by rememberUpdatedState<(Dp) -> Unit>({ renderedHeight = it })
    val shellFragment = if (incremental) incrementalShell.orEmpty() else fragment
    val document = remember(shellFragment, foreground, background, fontSize, incremental) {
        """<!doctype html><html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; script-src 'sha256-$HEIGHT_SCRIPT_HASH'; style-src 'unsafe-inline'; img-src 'none'; font-src 'none'; connect-src 'none'; form-action 'none'; base-uri 'none'">
<style>html,body{margin:0;padding:0;background:${background.cssColor()};color:${foreground.cssColor()};font-family:sans-serif;font-size:${fontSize.coerceIn(8, 72)}px;overflow-wrap:anywhere}${if (incremental) "html,body{max-width:100%;overflow-x:hidden;overflow-anchor:none}#companion-content{display:flow-root;max-width:100%;min-width:0}" else ""}</style>
</head><body><div id="companion-content">$shellFragment</div><script>$HEIGHT_SCRIPT</script></body></html>"""
    }
    AndroidView(
        factory = { context ->
            ContentSizedWebView(context).apply {
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
                        if (density > 0) updateHeight((cssHeight * view.scale / density).dp.coerceIn(80.dp, 16000.dp))
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
            // DOM growth reaches WebView before the measured height reaches Compose. The
            // temporary overflow must not become a second scroll surface inside the chat.
            // Keep the existing escape hatch for documents above the host's height limit.
            view.lockScrollToOrigin = incremental && renderedHeight < 16000.dp
            view.isVerticalScrollBarEnabled = !view.lockScrollToOrigin && view.defaultVerticalScrollBarEnabled
            view.isHorizontalScrollBarEnabled = !incremental && view.defaultHorizontalScrollBarEnabled
            view.overScrollMode = if (incremental) View.OVER_SCROLL_NEVER else view.defaultOverScrollMode
            if (incremental) {
                var state = view.tag as? IncrementalHtmlState
                if (state == null || state.document != document || state.targetId != incrementalTargetId) {
                    state = IncrementalHtmlState(document, fragment, incrementalTargetId, presentationKey, presentationStreaming)
                    view.tag = state
                    view.loadDataWithBaseURL(state.url, document, "text/html", "UTF-8", null)
                } else {
                    // A streaming row becomes a persisted/history row with a new UI
                    // identity. Keep the same document and measured height, but treat
                    // that identity transition as a fade reset rather than a reload.
                    val samePresentation = state.presentationKey == presentationKey
                    state.presentationKey = presentationKey
                    state.fragment = fragment
                    state.presentationStreaming = presentationStreaming
                    if (state.ready) view.applyLatestFragment(state, animateGrowthAllowed = samePresentation)
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

private class ContentSizedWebView(context: Context) : WebView(context) {
    val defaultVerticalScrollBarEnabled = isVerticalScrollBarEnabled
    val defaultHorizontalScrollBarEnabled = isHorizontalScrollBarEnabled
    val defaultOverScrollMode = overScrollMode
    var lockScrollToOrigin = false
        set(value) {
            field = value
            if (value && (scrollX != 0 || scrollY != 0)) super.scrollTo(0, 0)
        }

    override fun scrollTo(x: Int, y: Int) {
        if (lockScrollToOrigin) super.scrollTo(0, 0) else super.scrollTo(x, y)
    }

    override fun onOverScrolled(scrollX: Int, scrollY: Int, clampedX: Boolean, clampedY: Boolean) {
        if (lockScrollToOrigin) super.onOverScrolled(0, 0, clampedX, clampedY)
        else super.onOverScrolled(scrollX, scrollY, clampedX, clampedY)
    }

    override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
        super.onScrollChanged(l, t, oldl, oldt)
        if (lockScrollToOrigin && (l != 0 || t != 0)) super.scrollTo(0, 0)
    }
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
private class IncrementalHtmlState(
    val document: String,
    var fragment: String,
    val targetId: String,
    var presentationKey: String?,
    var presentationStreaming: Boolean,
) {
    val url = "https://companion.invalid/styled-body/${java.util.UUID.randomUUID()}/"
    var ready = false
    var applied: String? = null
    var appliedStreaming = false
}

private fun WebView.applyLatestFragment(state: IncrementalHtmlState, animateGrowthAllowed: Boolean = false) {
    if (animateGrowthAllowed && state.applied == state.fragment &&
        state.appliedStreaming == state.presentationStreaming
    ) return
    val fragment = state.fragment
    val animateGrowth = animateGrowthAllowed && state.applied != null &&
        state.appliedStreaming && state.presentationStreaming
    evaluateJavascript(BodyAppearanceScripts.replaceFragment(fragment, state.targetId, animateGrowth), null)
    state.applied = fragment
    state.appliedStreaming = state.presentationStreaming
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
