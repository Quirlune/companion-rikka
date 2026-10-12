package dev.gray.companion.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.graphics.Color
import java.util.TreeMap

internal data class StreamPresentationIdentity(
    val nodeId: String,
    val messageId: String,
    val candidateIndex: Int,
) {
    val stableKey: String get() = "$nodeId:$candidateIndex:$messageId"
}

internal data class StreamPresentationRequest(
    val identity: StreamPresentationIdentity,
    val isStreaming: Boolean,
)

internal data class StreamPresentationFrame(
    val identity: StreamPresentationIdentity,
    val text: String,
    val previousText: String,
    val appendStart: Int?,
    val revision: Long,
    val isStreaming: Boolean,
) {
    val isAppending: Boolean
        get() = isStreaming && appendStart != null && text.length > appendStart
}

internal val LocalStreamPresentationRequest = staticCompositionLocalOf<StreamPresentationRequest?> { null }
internal val LocalStreamPresentationFrame = staticCompositionLocalOf<StreamPresentationFrame?> { null }

/** Holds the previous rendered value without making it Compose state. */
internal class StreamFadeRangeState {
    private var previousText: String? = null
    private var cachedRevision = Long.MIN_VALUE
    private var cachedText: String? = null
    private var cachedSuffixStart: Int? = null
    private var hasCachedRange = false

    fun suffixStart(frame: StreamPresentationFrame, text: String): Int? {
        if (hasCachedRange && cachedRevision == frame.revision && cachedText == text) {
            return cachedSuffixStart
        }
        val previous = previousText
        val result = when {
            !frame.isAppending || previous == null -> null
            text.length <= previous.length || !text.startsWith(previous) -> null
            !isSafeStreamAppendBoundary(previous, text) -> null
            else -> previous.length
        }
        cachedRevision = frame.revision
        cachedText = text
        cachedSuffixStart = result
        hasCachedRange = true
        return result
    }

    fun recordDisplayedText(text: String) {
        previousText = text
    }
}

/** Keeps only the newest display snapshot; it never queues model deltas. */
internal class StreamPresentationBuffer(
    private val identity: StreamPresentationIdentity,
    initialText: String,
) {
    private var text = initialText
    private var previousText = initialText
    private var appendStart: Int? = null
    private var revision = 0L
    private var isStreaming = false

    fun publish(latestText: String): StreamPresentationFrame {
        if (latestText != text || !isStreaming) {
            previousText = text
            appendStart = text.length.takeIf {
                latestText.length > text.length &&
                    latestText.startsWith(text) &&
                    isSafeStreamAppendBoundary(text, latestText)
            }
            text = latestText
            revision++
        }
        isStreaming = true
        return snapshot()
    }

    fun flush(latestText: String): StreamPresentationFrame {
        if (latestText != text || isStreaming) {
            previousText = latestText
            text = latestText
            appendStart = null
            revision++
        }
        isStreaming = false
        return snapshot()
    }

    fun current(isStreaming: Boolean): StreamPresentationFrame = if (isStreaming) {
        snapshot().copy(isStreaming = true)
    } else {
        StreamPresentationFrame(
            identity = identity,
            text = text,
            previousText = text,
            appendStart = null,
            revision = revision,
            isStreaming = false,
        )
    }

    private fun snapshot() = StreamPresentationFrame(
        identity = identity,
        text = text,
        previousText = previousText,
        appendStart = appendStart,
        revision = revision,
        isStreaming = isStreaming,
    )
}

@Composable
internal fun rememberStreamPresentation(
    latestText: String,
    request: StreamPresentationRequest?,
): StreamPresentationFrame? {
    if (request == null) return null
    val buffer = remember(request.identity) { StreamPresentationBuffer(request.identity, latestText) }
    val latest by rememberUpdatedState(latestText)
    var frame by remember(buffer) { mutableStateOf(buffer.current(request.isStreaming)) }

    LaunchedEffect(buffer, request.isStreaming) {
        if (!request.isStreaming) {
            frame = buffer.flush(latest)
            return@LaunchedEffect
        }

        while (true) {
            withFrameNanos { }
            val next = buffer.publish(latest)
            if (next.revision != frame.revision || next.text != frame.text) frame = next
        }
    }

    SideEffect {
        if (!request.isStreaming) {
            val flushed = buffer.flush(latest)
            if (flushed != frame) frame = flushed
        }
    }

    return if (request.isStreaming) frame else StreamPresentationFrame(
        identity = request.identity,
        text = latest,
        previousText = latest,
        appendStart = null,
        revision = frame.revision + if (frame.isStreaming || frame.text != latest) 1 else 0,
        isStreaming = false,
    )
}

/** Applies alpha only to a newly appended suffix; all existing SpanStyles remain intact. */
@Composable
internal fun streamFadeText(text: AnnotatedString): AnnotatedString {
    val frame = LocalStreamPresentationFrame.current ?: return text
    val fadeRange = remember(frame.identity) { StreamFadeRangeState() }
    val suffixStart = fadeRange.suffixStart(frame, text.text)
    SideEffect { fadeRange.recordDisplayedText(text.text) }
    if (suffixStart == null || suffixStart >= text.length) return text

    val alpha = remember(frame.identity, frame.revision, suffixStart, text.text) { Animatable(0f) }
    LaunchedEffect(alpha) { alpha.animateTo(1f, tween(STREAM_FADE_DURATION_MS)) }
    val inheritedColor = LocalTextStyle.current.color.takeIf { it != Color.Unspecified }
        ?: LocalContentColor.current
    val explicitSpans = text.spanStyles.withIndex().filter { (_, span) ->
        span.start < text.length && span.end > suffixStart &&
            (span.item.brush != null || span.item.color != Color.Unspecified)
    }
    val spansStarting = explicitSpans.filter { it.value.start >= suffixStart }.groupBy { it.value.start }
    val spansEnding = explicitSpans.groupBy { it.value.end }
    val boundaries = buildSet {
        add(suffixStart)
        add(text.length)
        explicitSpans.forEach { (_, span) ->
            if (span.start in (suffixStart + 1) until text.length) add(span.start)
            if (span.end in (suffixStart + 1) until text.length) add(span.end)
        }
    }.sorted()
    return buildAnnotatedString {
        append(text)
        val activeForegrounds = TreeMap<Int, SpanStyle>()
        explicitSpans.filter { it.value.start < suffixStart }.forEach {
            activeForegrounds[it.index] = it.value.item
        }
        boundaries.zipWithNext().forEach { (start, end) ->
            if (start >= end) return@forEach
            spansEnding[start]?.forEach { activeForegrounds.remove(it.index) }
            spansStarting[start]?.forEach { activeForegrounds[it.index] = it.value.item }
            // SpanStyle's color constructor has no alpha argument. Resolve each run's
            // foreground first, then overlay only that foreground so weight, style,
            // decoration and the original brush/color continue to apply.
            val foreground = activeForegrounds.lastEntry()?.value
            val brush = foreground?.brush
            if (brush != null && foreground != null) {
                // Compose uses NaN for an unspecified brush alpha; treat that as
                // the normal fully opaque default before applying the fade.
                val foregroundAlpha = foreground.alpha.takeIf { it.isFinite() } ?: 1f
                addStyle(
                    SpanStyle(brush = brush, alpha = (foregroundAlpha * alpha.value).coerceIn(0f, 1f)),
                    start,
                    end,
                )
            } else {
                val color = foreground?.color?.takeIf { it != Color.Unspecified } ?: inheritedColor
                addStyle(SpanStyle(color = color.copy(alpha = color.alpha * alpha.value)), start, end)
            }
        }
    }
}

internal const val STREAM_FADE_DURATION_MS = 140

private fun isSafeStreamAppendBoundary(previous: String, next: String): Boolean {
    val boundary = previous.length
    if (boundary == 0 || boundary >= next.length) return true
    return !(previous.last() in '\uD800'..'\uDBFF' && next[boundary] in '\uDC00'..'\uDFFF')
}
