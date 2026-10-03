package dev.gray.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.max
import kotlin.math.min

private class MacroMenuPositionProvider(
    private val margin: Int,
    private val imeBottom: Int,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val visibleBottom = (windowSize.height - imeBottom - margin).coerceAtLeast(margin)
        val below = anchorBounds.bottom + margin
        val above = anchorBounds.top - popupContentSize.height - margin
        val y = when {
            below + popupContentSize.height <= visibleBottom -> below
            above >= margin && above + popupContentSize.height <= visibleBottom -> above
            else -> (visibleBottom - popupContentSize.height).coerceAtLeast(margin)
        }
        val preferredX = if (layoutDirection == LayoutDirection.Ltr) anchorBounds.left else anchorBounds.right - popupContentSize.width
        val maxX = (windowSize.width - popupContentSize.width - margin).coerceAtLeast(margin)
        return IntOffset(preferredX.coerceIn(margin, maxX), y)
    }
}

/** Selection and completion are UI state; the stored template remains ordinary text. */
@Composable
internal fun PromptTemplateField(
    label: String,
    value: String,
    references: List<PromptReferenceChoice>,
    onChange: (String) -> Unit,
    minLines: Int = 3,
    transformMacro: (String) -> String = { it },
) {
    var draft by remember { mutableStateOf(TextFieldValue(value)) }
    var focused by remember { mutableStateOf(false) }
    var explicitlyOpen by remember { mutableStateOf(false) }
    var browserOpen by remember { mutableStateOf(false) }
    var dismissedAt by remember { mutableStateOf<Pair<String, TextRange>?>(null) }
    var fieldBounds by remember { mutableStateOf(Rect.Zero) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val density = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    val imeBottom = WindowInsets.ime.getBottom(density)
    val marginPx = with(density) { 8.dp.roundToPx() }
    val token = remember(draft.text, draft.selection) {
        macroCompletionToken(draft.text, draft.selection.start, draft.selection.end)
    }
    val popupOpen = !browserOpen && (explicitlyOpen || (focused && token != null && dismissedAt != (draft.text to draft.selection)))
    val visibleBottom = (windowSize.height - imeBottom - marginPx).coerceAtLeast(marginPx + 1)
    val availableHeight = max(
        fieldBounds.top - marginPx * 2,
        visibleBottom - fieldBounds.bottom - marginPx,
    ).coerceIn(1f, (visibleBottom - marginPx).coerceAtLeast(1).toFloat())
    val popupHeight = with(density) {
        val viewportHeight = (visibleBottom - marginPx).coerceAtLeast(1).toFloat()
        val usableHeight = if (availableHeight < 120.dp.toPx()) viewportHeight else availableHeight
        min(360.dp.toPx(), usableHeight).toDp()
    }
    val popupWidth = with(density) {
        max(fieldBounds.width, 280.dp.toPx()).coerceAtMost((windowSize.width - marginPx * 2).coerceAtLeast(1).toFloat()).toDp()
    }
    val positionProvider = remember(marginPx, imeBottom) { MacroMenuPositionProvider(marginPx, imeBottom) }
    LaunchedEffect(value) {
        if (draft.text != value) {
            draft = TextFieldValue(value, TextRange(draft.selection.end.coerceIn(0, value.length)))
            explicitlyOpen = false
        }
    }
    fun dismissCompletion() {
        explicitlyOpen = false
        dismissedAt = draft.text to draft.selection
    }
    fun insert(macro: String) {
        val edited = replaceMacroCompletion(draft.text, draft.selection.start, draft.selection.end, transformMacro(macro))
        draft = TextFieldValue(edited.text, TextRange(edited.caret))
        explicitlyOpen = false
        browserOpen = false
        dismissedAt = draft.text to draft.selection
        onChange(edited.text)
    }
    BackHandler(enabled = popupOpen) { dismissCompletion() }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = draft,
            onValueChange = { changed ->
                draft = changed
                if (changed.text != value) onChange(changed.text)
            },
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { state ->
                    focused = state.isFocused
                    if (!state.isFocused) explicitlyOpen = false
                }
                .onGloballyPositioned { fieldBounds = it.boundsInWindow() }
                .onPreviewKeyEvent {
                    if (popupOpen && it.type == KeyEventType.KeyDown && it.key == Key.Escape) {
                        dismissCompletion()
                        true
                    } else false
                },
            trailingIcon = {
                IconButton(
                    onClick = { if (popupOpen) dismissCompletion() else explicitlyOpen = true },
                    modifier = Modifier.focusProperties { canFocus = false }.semantics { contentDescription = "选取宏" },
                ) {
                    Text("{}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge)
                }
            },
            minLines = minLines,
            shape = MaterialTheme.shapes.large,
        )
        if (popupOpen && fieldBounds != Rect.Zero) {
            Popup(
                popupPositionProvider = positionProvider,
                onDismissRequest = ::dismissCompletion,
                properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = true),
            ) {
                MacroCompletionMenu(
                    references, token?.query.orEmpty(), popupWidth, popupHeight,
                    onInsert = ::insert,
                    onOpenBrowser = {
                        dismissCompletion()
                        browserOpen = true
                    },
                    onDismiss = ::dismissCompletion,
                )
            }
        }
    }
    if (browserOpen) {
        PromptReferenceSheet(
            references,
            onInsert = { macro -> insert(macro); focusRequester.requestFocus(); keyboard?.show() },
            onDismiss = { browserOpen = false; focusRequester.requestFocus() },
            includeMacroHelp = true,
        )
    }
}
