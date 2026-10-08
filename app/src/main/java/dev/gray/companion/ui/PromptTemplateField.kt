package dev.gray.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import dev.gray.companion.core.HistorySelection
import dev.gray.companion.core.HistoryViews
import dev.gray.companion.core.MacroTemplateTokens
import kotlin.math.max
import kotlin.math.min

private data class HistoryReferenceEdit(
    val source: String, val start: Int, val end: Int, val selection: HistorySelection,
    val soleStructuredBlock: Boolean,
)

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
    var historyEdit by remember { mutableStateOf<HistoryReferenceEdit?>(null) }
    var referenceEdit by remember { mutableStateOf<PromptReferenceOccurrence?>(null) }
    var browserTarget by remember { mutableStateOf<PromptReferenceOccurrence?>(null) }
    var referenceNotice by remember { mutableStateOf<String?>(null) }
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
    val scan = remember(draft.text) { MacroTemplateTokens.scan(draft.text) }
    val occurrences = remember(draft.text, scan, references) { promptReferenceOccurrences(draft.text, scan, references) }
    val problems = remember(draft.text, scan) { promptReferenceProblems(draft.text, scan) }
    val popupOpen = !browserOpen && historyEdit == null && referenceEdit == null &&
        (explicitlyOpen || (focused && token != null && dismissedAt != (draft.text to draft.selection)))
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
    fun targetIsCurrent(target: PromptReferenceOccurrence): Boolean {
        if (target.matches(draft.text) && target.matches(value)) return true
        referenceNotice = "文本已改变，请重新选择这一处引用。"
        referenceEdit = null
        browserOpen = false
        return false
    }
    fun selectSpan(start: Int, end: Int, editText: Boolean) {
        draft = draft.copy(selection = TextRange(start.coerceIn(0, draft.text.length), end.coerceIn(0, draft.text.length)))
        dismissCompletion()
        if (editText) { focusRequester.requestFocus(); keyboard?.show() }
    }
    fun insert(macro: String, target: PromptReferenceOccurrence? = null) {
        if (target != null && !targetIsCurrent(target)) return
        val edited = if (target == null) replaceMacroCompletion(draft.text, draft.selection.start, draft.selection.end, transformMacro(macro))
        else {
            val inserted = transformMacro(macro)
            MacroCompletionEdit(target.source.replaceRange(target.start, target.endExclusive, inserted), target.start + inserted.length)
        }
        draft = TextFieldValue(edited.text, TextRange(edited.caret))
        explicitlyOpen = false
        browserOpen = false
        dismissedAt = draft.text to draft.selection
        referenceNotice = null
        onChange(edited.text)
    }
    fun openHistory(reference: String? = null, target: PromptReferenceOccurrence? = null) {
        if (target != null && !targetIsCurrent(target)) return
        val currentToken = macroCompletionToken(draft.text, draft.selection.start, draft.selection.end)
        val currentExpression = target?.text ?: currentToken?.let { draft.text.substring(it.start, it.end) }
        val selection = reference?.let { runCatching { HistoryViews.selection(it) }.getOrNull() }
            ?: currentExpression?.let { runCatching { HistoryViews.selection(it) }.getOrNull() } ?: HistorySelection(compressed = true)
        val start = target?.start ?: currentToken?.start ?: minOf(draft.selection.start, draft.selection.end).coerceIn(0, draft.text.length)
        val end = target?.endExclusive ?: currentToken?.end ?: maxOf(draft.selection.start, draft.selection.end).coerceIn(start, draft.text.length)
        val sole = draft.text.substring(0, start).isBlank() && draft.text.substring(end).isBlank()
        historyEdit = HistoryReferenceEdit(draft.text, start, end, selection, sole)
        referenceEdit = null
        dismissCompletion()
        browserOpen = false
        keyboard?.hide()
    }
    fun choose(macro: String, target: PromptReferenceOccurrence? = null) {
        if (runCatching { HistoryViews.selection(macro) }.getOrNull() != null) openHistory(macro, target) else insert(macro, target)
    }
    BackHandler(enabled = popupOpen) { dismissCompletion() }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = draft,
            onValueChange = { changed ->
                draft = changed
                referenceNotice = null
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
                    onInsert = { choose(it) },
                    onConfigureHistory = { openHistory() },
                    onOpenBrowser = {
                        dismissCompletion()
                        browserTarget = null
                        browserOpen = true
                    },
                    onDismiss = ::dismissCompletion,
                )
            }
        }
    }
    PromptReferenceChips(occurrences) { occurrence ->
        if (targetIsCurrent(occurrence)) {
            selectSpan(occurrence.start, occurrence.endExclusive, editText = false)
            referenceNotice = null
            referenceEdit = occurrence
            keyboard?.hide()
        }
    }
    problems.forEach { problem ->
        TextButton(onClick = { selectSpan(problem.start, problem.endExclusive, editText = true) },
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
            Text(problem.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
    referenceNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    referenceEdit?.let { target ->
        PromptReferenceActions(target,
            onReplace = {
                if (targetIsCurrent(target)) { referenceEdit = null; browserTarget = target; browserOpen = true }
            },
            onHistory = if (target.history == null) null else ({ openHistory(target = target) }),
            onEditText = {
                if (targetIsCurrent(target)) {
                    referenceEdit = null
                    selectSpan(target.start, target.endExclusive, editText = true)
                }
            },
            onDelete = {
                if (targetIsCurrent(target)) {
                    val changed = target.source.removeRange(target.start, target.endExclusive)
                    draft = TextFieldValue(changed, TextRange(target.start))
                    referenceEdit = null
                    referenceNotice = null
                    dismissCompletion()
                    onChange(changed)
                }
            },
            onDismiss = { referenceEdit = null })
    }
    if (browserOpen) {
        PromptReferenceSheet(
            references,
            onInsert = { macro ->
                choose(macro, browserTarget)
                if (historyEdit == null) { focusRequester.requestFocus(); keyboard?.show() }
            },
            onDismiss = { browserOpen = false; focusRequester.requestFocus() },
            includeMacroHelp = true,
            onConfigureHistory = { openHistory(target = browserTarget) },
        )
    }
    historyEdit?.let { edit ->
        HistoryReferenceEditor(edit.selection, references, edit.soleStructuredBlock,
            onSave = { expression ->
                if (draft.text == edit.source && value == edit.source) {
                    val inserted = transformMacro(expression)
                    val changed = edit.source.replaceRange(edit.start, edit.end, inserted)
                    draft = TextFieldValue(changed, TextRange(edit.start + inserted.length))
                    dismissedAt = draft.text to draft.selection
                    onChange(changed)
                } else referenceNotice = "文本已改变，请重新选择这一处历史引用。"
                historyEdit = null
                focusRequester.requestFocus()
            },
            onDismiss = { historyEdit = null; focusRequester.requestFocus() })
    }
}
