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
import dev.gray.companion.core.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import dev.gray.companion.core.HistorySelection
import dev.gray.companion.core.HistoryViews
import dev.gray.companion.core.MacroTemplateTokens
import kotlin.math.max
import kotlin.math.min

private data class HistoryReferenceEdit(
    val source: String, val start: Int, val end: Int, val selection: HistorySelection,
    val soleStructuredBlock: Boolean,
)

internal fun isTextHistoryReference(expression: String): Boolean = HistoryReferences.isText(expression)

internal fun shouldOpenHistoryReferenceEditor(expression: String): Boolean =
    runCatching { HistoryViews.selection(expression) }.getOrNull() != null

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
    owner: ExtensionFieldRef? = null,
    maxLines: Int = Int.MAX_VALUE,
) {
    val navigation = LocalSourceNavigation.current
    val previewMacro = rememberPromptMacroPreview()
    var sourceChoices by remember { mutableStateOf<List<ResourceLocation>?>(null) }
    var separateTarget by remember { mutableStateOf<PromptReferenceOccurrence?>(null) }
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
    val occurrences = remember(draft.text, scan, references, navigation?.library, owner) {
        promptReferenceOccurrences(draft.text, scan, references).map { occurrence ->
            val resolved = navigation?.library?.let { ResourceLocations.resolveReference(it, owner, occurrence.text) }.orEmpty()
            occurrence.copy(locations = (occurrence.locations + resolved).distinctBy { it.copy(section = null) })
        }
    }
    val currentOccurrence = occurrences.singleOrNull { occurrence ->
        if (draft.selection.collapsed) draft.selection.start >= occurrence.start && draft.selection.start < occurrence.endExclusive
        else draft.selection.min >= occurrence.start && draft.selection.max <= occurrence.endExclusive
    }
    val macroPreviews = remember(occurrences, previewMacro) {
        occurrences.associate { occurrence -> occurrence.start to previewMacro(occurrence.text) }
    }
    val previewLabels = remember(macroPreviews) {
        macroPreviews.mapValues { (_, preview) ->
            preview.unavailableReason ?: preview.issues.firstOrNull { it.error }?.message
                ?: compactMacroPreview(preview.text).ifBlank { "暂无内容" }
        }
    }
    val macroTransformation = PromptMacroVisualTransformation(previewLabels,
        if (focused) currentOccurrence?.start else null,
        androidx.compose.ui.graphics.Color(0xFFDCEAFF), androidx.compose.ui.graphics.Color(0xFF173C70))
    val problems = remember(draft.text, scan) { promptReferenceProblems(draft.text, scan) }
    val popupOpen = !browserOpen && historyEdit == null && referenceEdit == null &&
        (explicitlyOpen || (focused && token?.shortcut == true && dismissedAt != (draft.text to draft.selection)))
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
    val requestedFocus = LocalSourceFieldFocus.current ?: navigation?.focus
    LaunchedEffect(requestedFocus, owner) {
        if (owner != null && requestedFocus != null && requestedFocus.field == owner) {
            val start = requestedFocus.spanStart
            val end = requestedFocus.spanEndExclusive
            if (start != null && end != null && start in 0..draft.text.length && end in start..draft.text.length) {
                draft = draft.copy(selection = TextRange(start, end))
                dismissedAt = draft.text to draft.selection
                focusRequester.requestFocus()
            }
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
        if (shouldOpenHistoryReferenceEditor(macro)) openHistory(macro, target) else insert(macro, target)
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
            visualTransformation = macroTransformation,
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
                    onClick = {
                        val current = currentOccurrence
                        if (current != null && targetIsCurrent(current)) {
                            dismissCompletion()
                            referenceEdit = current
                            keyboard?.hide()
                        } else if (popupOpen) dismissCompletion() else explicitlyOpen = true
                    },
                    modifier = Modifier.focusProperties { canFocus = false }.semantics {
                        contentDescription = if (currentOccurrence == null) "插入宏" else "查看与编辑当前宏"
                    },
                ) {
                    Text("//", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge)
                }
            },
            minLines = minLines,
            maxLines = maxLines,
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
    problems.forEach { problem ->
        TextButton(onClick = { selectSpan(problem.start, problem.endExclusive, editText = true) },
            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) {
            Text(problem.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
    referenceNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    referenceEdit?.let { target ->
        val preview = macroPreviews[target.start] ?: previewMacro(target.text)
        PromptReferenceActions(target, preview = preview,
            onLiteral = if (preview.unavailableReason == null && preview.issues.none { it.error } && target.history == null) ({
                if (targetIsCurrent(target)) {
                    // Braces in expanded source would become a new template: keep this action unavailable for them.
                    val changed = target.source.replaceRange(target.start, target.endExclusive, preview.text)
                    draft = TextFieldValue(changed, TextRange(target.start + preview.text.length))
                    referenceEdit = null
                    onChange(changed)
                }
            }).takeIf { "{{" !in preview.text && "}}" !in preview.text } else null,
            onSource = preview.editSource ?: if (navigation != null && target.locations.isNotEmpty()) ({
                if (targetIsCurrent(target)) {
                    referenceEdit = null
                    if (target.locations.size == 1) navigation.edit(target.locations.single()) else sourceChoices = target.locations
                }
            }) else null,
            onSeparate = if (navigation != null && owner != null && target.locations.singleOrNull()?.field?.field == ExtensionField.ENTRY_TEXT) ({
                if (targetIsCurrent(target)) { referenceEdit = null; separateTarget = target }
            }) else null,
            onReplace = {
                if (targetIsCurrent(target)) { referenceEdit = null; browserTarget = target; browserOpen = true }
            },
            onHistory = if (shouldOpenHistoryReferenceEditor(target.text)) ({ openHistory(target = target) }) else null,
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
                val target = browserTarget
                browserOpen = false
                browserTarget = null
                choose(macro, target)
                if (historyEdit == null) { focusRequester.requestFocus(); keyboard?.show() }
            },
            onDismiss = {
                // The picker also calls onDismiss after onInsert. A committed choice must not
                // reopen the old actions sheet over the newly opened history editor.
                if (browserOpen) {
                    val target = browserTarget
                    browserOpen = false
                    browserTarget = null
                    if (target != null) referenceEdit = target else focusRequester.requestFocus()
                }
            },
            includeMacroHelp = true,
            onConfigureHistory = {
                val target = browserTarget
                browserOpen = false
                browserTarget = null
                openHistory(target = target)
            },
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
    sourceChoices?.let { choices ->
        ModalBottomSheet(onDismissRequest = { sourceChoices = null }) {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState()).padding(16.dp)) {
                Text("选择实际来源", style = MaterialTheme.typography.titleLarge)
                Text("这个地址有多个来源；请选择要编辑的那一项。", style = MaterialTheme.typography.bodySmall)
                choices.forEach { location ->
                    TextButton(onClick = { sourceChoices = null; navigation?.edit(location) }) {
                        val call = location.workflowId?.let { navigation?.library?.sourceWorkflow(location.resource, it) }
                        Text(listOfNotNull(navigation?.library?.let { sourceResourceTitle(it, location.resource) }, call?.title,
                            call?.nodes?.firstOrNull { it.id == location.nodeId }?.title).joinToString(" · "))
                    }
                }
            }
        }
    }
    separateTarget?.let { occurrence ->
        val source = occurrence.locations.singleOrNull()?.field?.takeIf { it.field == ExtensionField.ENTRY_TEXT }
        val plan = remember(navigation?.library, owner, source, occurrence) { if (navigation != null && owner != null && source != null)
            if (source.kind == owner.kind && source.resourceId == owner.resourceId)
                ResourceEditCommands.previewSeparateDraft(navigation.library, owner, occurrence.source, occurrence.start, occurrence.endExclusive, source)
            else ResourceEditCommands.previewSeparate(navigation.library, owner, occurrence.source, occurrence.start, occurrence.endExclusive, source) else null }
        val issues = plan?.issues.orEmpty()
        AlertDialog(onDismissRequest = { separateTarget = null }, title = { Text("仅这一处独立使用") }, text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("只替换当前引用；其他引用保留原词条。")
                if (source == null) Text("当前引用没有唯一的静态词条来源；历史、动态结果及多来源引用不能在此独立。", color = MaterialTheme.colorScheme.error)
                issues.forEach { Text(it.message, color = MaterialTheme.colorScheme.error) }
                plan?.changes.orEmpty().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }, confirmButton = { TextButton(enabled = plan?.canApply == true || plan?.draftLibrary != null, onClick = {
            if (plan != null && navigation != null && targetIsCurrent(occurrence)) {
                val result = if (plan.draftLibrary == null) ResourceEditCommands.apply(plan, navigation.library) else null
                val candidate = plan.draftLibrary ?: result?.library
                if (candidate == null) referenceNotice = result?.issues.orEmpty().joinToString("\n") { it.message }
                else plan.destination?.let { destination ->
                    // Foreign source + owner are committed together through the full draft leave chain.
                    if (source?.resourceId == owner?.resourceId && source?.kind == owner?.kind && navigation.canMerge(destination)) {
                        navigation.merge(candidate)
                        navigation.edit(destination)
                    } else navigation.leaveAtomic(candidate, destination) { ok -> if (!ok) referenceNotice = "尚未独立使用；草稿已保留。" }
                }
                separateTarget = null
            }
        }) { Text("创建独立词条") } }, dismissButton = { TextButton(onClick = { separateTarget = null }) { Text("取消") } })
    }

}
