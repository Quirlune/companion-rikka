package dev.gray.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.activity.compose.BackHandler
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.input.PasswordVisualTransformation
import dev.gray.companion.core.*
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.FloppyDisk
import me.rerere.hugeicons.stroke.ArrowDown01
import me.rerere.hugeicons.stroke.ArrowUp01
import me.rerere.hugeicons.stroke.Delete01
import me.rerere.rikkahub.ui.components.ui.Select
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.ui.theme.CustomColors

@Composable
internal fun TextFieldLine(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, minLines: Int = 1) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, modifier = modifier.fillMaxWidth(), minLines = minLines, shape = MaterialTheme.shapes.large)
}

@Composable
internal fun ToggleLine(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large),
        colors = CustomColors.listItemColors,
    )
}

@Composable
internal fun <T> ChoiceLine(label: String, selected: T, choices: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (choices.isEmpty()) Text("暂无可选项", style = MaterialTheme.typography.bodyMedium)
        else Select(
            options = choices,
            selectedOption = choices.firstOrNull { it.first == selected } ?: (selected to "未设置"),
            onOptionSelected = { onSelect(it.first) },
            optionToString = { it.second },
        )
    }
}

@Composable
internal fun MoveButtons(index: Int, count: Int, onUp: () -> Unit, onDown: () -> Unit, onRemove: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (count > 1) {
            IconButton(onClick = onUp, enabled = index > 0) { Icon(HugeIcons.ArrowUp01, "上移") }
            IconButton(onClick = onDown, enabled = index < count - 1) { Icon(HugeIcons.ArrowDown01, "下移") }
        }
        IconButton(onClick = onRemove) { Icon(HugeIcons.Delete01, "删除", tint = MaterialTheme.colorScheme.error) }
    }
}

internal fun <T> List<T>.moved(from: Int, to: Int): List<T> = toMutableList().apply { add(to, removeAt(from)) }

/** Collapsing changes presentation only; configured values remain owned by the caller's draft. */
@Composable
internal fun EditorSection(
    title: String,
    summary: String? = null,
    initiallyExpanded: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(initiallyExpanded) }
    val focus = LocalFocusManager.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .clickable(onClickLabel = if (expanded) "收起$title" else "展开$title") {
                    focus.clearFocus()
                    expanded = !expanded
                }
                .padding(horizontal = 4.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                summary?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis) }
            }
            Icon(
                if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01,
                contentDescription = if (expanded) "收起$title" else "展开$title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        if (expanded) Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
internal fun ConditionForm(condition: Condition?, onChange: (Condition?) -> Unit) {
    ToggleLine("启用条件", condition != null) { onChange(if (it) Condition() else null) }
    if (condition != null) {
        if (condition.expression.isBlank()) {
            TextFieldLine("状态键", condition.key, { onChange(condition.copy(key = it)) })
            ChoiceLine("比较", condition.op, listOf(
                Compare.EQUALS to "等于", Compare.NOT_EQUALS to "不等于",
                Compare.GREATER to "大于", Compare.GREATER_OR_EQUAL to "大于等于",
                Compare.LESS to "小于", Compare.LESS_OR_EQUAL to "小于等于", Compare.EXISTS to "存在"
            )) { onChange(condition.copy(op = it)) }
            if (condition.op != Compare.EXISTS) TextFieldLine("比较值", condition.value, { onChange(condition.copy(value = it)) })
            if (condition.key.isBlank()) Text("填写状态名称后，这个条件才会参与判断。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("当前按高级表达式判断", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        var expressionExpanded by remember { mutableStateOf(condition.expression.isNotBlank()) }
        TextButton(onClick = { expressionExpanded = !expressionExpanded }) { Text(if (expressionExpanded) "收起表达式" else "高级表达式") }
        if (expressionExpanded) {
            val expressionError = condition.expression.takeIf { it.isNotBlank() }?.let(ConditionExpressions::validate)
            OutlinedTextField(condition.expression, { onChange(condition.copy(expression = it)) }, label = { Text("条件表达式") },
                isError = expressionError != null, modifier = Modifier.fillMaxWidth(), minLines = 2)
            if (expressionError != null) Text(expressionError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Text("可组合 state / result / input 与 &&、||、!、exists()。仅计算本地状态。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (condition.expression.isNotBlank()) TextButton(onClick = { onChange(condition.copy(expression = "")) }) { Text("清空表达式，改用简单比较") }
        }
    }
}

@Composable
internal fun MacroHelp(onInsert: (String) -> Unit) {
    Text("插入宏", style = MaterialTheme.typography.labelLarge)
    val macros = listOf("{{input}}", "{{character.name}}") +
        CharacterFields.definitions.map { "{{character.${it.key}}}" } +
        listOf("{{category.name}}", "{{result.key}}", "{{macro.key}}", "{{results.category}}", "{{state.key}}", "{{pack.namespace.key}}")
    macros.chunked(3).forEach { row ->
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) { row.forEach { macro ->
            SuggestionChip(onClick = { onInsert(macro) }, label = { Text(macro, style = MaterialTheme.typography.labelSmall) })
        } }
    }
    Text("可选历史引用：@turn-1、@update-1、@version-12，写在 #XML 路径之前；update 只计算已记录的轨迹点。例：{{macro.mood@turn-1}}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun EditorPage(title: String, onBack: () -> Unit, onSave: () -> Unit, dirty: Boolean = false, saving: Boolean = false, contentSpacing: Dp = 16.dp,
    titleContent: (@Composable () -> Unit)? = null, scrollKey: Any? = null,
    navigationContent: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    var confirmBack by remember { mutableStateOf(false) }
    var showIssues by remember { mutableStateOf(false) }
    val sourceNavigation = LocalSourceNavigation.current
    val sourceIssues = LocalSourceIssues.current
    val focus = LocalFocusManager.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    fun requestBack() { if (!saving) { if (dirty) confirmBack = true else onBack() } }
    BackHandler { requestBack() }
    LaunchedEffect(saving) { if (saving) focus.clearFocus(force = true) }
    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { if (titleContent != null) titleContent() else Text(title, maxLines = 1) },
                navigationIcon = { CompanionBackButton(onClick = ::requestBack, enabled = !saving) },
                actions = {
                    if (sourceIssues.isNotEmpty() && sourceNavigation != null) TextButton(onClick = { showIssues = true }) { Text("问题 ${sourceIssues.size}") }
                    if (dirty && !saving) Text("未保存", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    IconButton(onClick = onSave, enabled = !saving) {
                        if (saving) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(HugeIcons.FloppyDisk, contentDescription = "保存")
                    }
                },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize()) {
                navigationContent?.invoke()
                key(scrollKey) {
                    Column(Modifier.weight(1f).fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(contentSpacing), content = content)
                }
            }
            if (saving) Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.65f), modifier = Modifier.fillMaxSize().pointerInput(Unit) {
                awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
            }) { Box(contentAlignment = androidx.compose.ui.Alignment.Center) { CircularProgressIndicator() } }
        }
    }
    if (showIssues && sourceNavigation != null) SourceIssuesSheet(sourceNavigation.library, sourceIssues, { location -> if (location.field == null) sourceNavigation.open(location) else sourceNavigation.edit(location) }, { showIssues = false })
    if (confirmBack) AlertDialog(
        onDismissRequest = { confirmBack = false }, title = { Text("有未保存的修改") },
        text = { Text("保存后再返回，或继续编辑。") },
        confirmButton = { TextButton(onClick = { confirmBack = false; onSave() }, enabled = !saving) { Text("保存并返回") } },
        dismissButton = { Row { TextButton(onClick = { confirmBack = false }) { Text("继续编辑") }; TextButton(onClick = { confirmBack = false; onBack() }) { Text("放弃修改") } } }
    )
}

@Composable
internal fun EditorSectionTabs(titles: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    TabRow(selectedTabIndex = selected) {
        titles.forEachIndexed { index, title ->
            Tab(selected = index == selected, onClick = { onSelect(index) }, text = { Text(title) })
        }
    }
}

/** Shared binding picker; filtering never changes unseen selections. */
@Composable
internal fun ResourceBindingList(
    choices: List<Pair<String, String>>,
    selectedIds: List<String>,
    onChange: (List<String>) -> Unit,
) {
    var search by rememberSaveable { mutableStateOf("") }
    if (choices.isEmpty()) {
        Text("暂无可关联的资源", style = MaterialTheme.typography.bodySmall)
        return
    }
    if (choices.size > 5) TextFieldLine("搜索资源", search, { search = it })
    val filtered = remember(choices, search) { choices.filter { search.isBlank() || it.second.contains(search.trim(), true) } }
    if (filtered.isEmpty()) Text("没有匹配的资源", style = MaterialTheme.typography.bodySmall)
    filtered.forEach { (id, name) -> key(id) {
        ToggleLine(name, id in selectedIds) { checked ->
            onChange(if (checked) (selectedIds + id).distinct() else selectedIds - id)
        }
    } }
}
