package dev.gray.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.*
import me.rerere.rikkahub.ui.theme.CustomColors

/** A child page edits the same character draft; only the parent resource page publishes it. */
@Composable
internal fun CharacterBasicSettingsPage(
    card: CharacterCard,
    tags: List<PromptTag>,
    references: List<PromptReferenceChoice>,
    focusedEntryId: String?,
    onChange: (CharacterCard) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        containerColor = CustomColors.topBarColors.containerColor,
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("基础设定") },
                navigationIcon = { CompanionBackButton(onClick = onBack) },
                actions = { TextButton(onClick = onBack) { Text("完成") } },
                scrollBehavior = scrollBehavior,
                colors = CustomColors.topBarColors,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()
            .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(card.name, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            CharacterFields.definitions.forEach { field ->
                val entry = card.entries.first { it.key == field.key }
                key(entry.id, focusedEntryId == entry.id) {
                    val title = entry.title.ifBlank { field.title }
                    val summary = entry.text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(80)
                        ?: "尚未填写"
                    EditorSection(title, summary, initiallyExpanded = focusedEntryId == entry.id) {
                        var rename by remember(entry.id) { mutableStateOf(false) }
                        fun update(changed: PromptEntry) {
                            onChange(card.copy(entries = card.entries.map { if (it.id == entry.id) changed else it }))
                        }
                        EditorTitleRow("$title / ${entry.slug}", { rename = true }) {
                            PromptTagSelector(tags, entry.tagIds, compact = true) { update(entry.copy(tagIds = it)) }
                        }
                        if (rename) NamingDialog("${field.title}名称", title, entry.slug,
                            card.entries.filterNot { it.id == entry.id }.map { it.title },
                            card.entries.filterNot { it.id == entry.id }.map { it.slug },
                            { rename = false }) { changedTitle, slug ->
                            update(entry.copy(title = changedTitle, slug = slug)); rename = false
                        }
                        PromptTemplateField(field.title, entry.text, references,
                            { update(entry.copy(text = it)) }, minLines = 3, maxLines = 12,
                            owner = ExtensionFieldRef(ExtensionResourceKind.CHARACTER, card.id, ExtensionField.ENTRY_TEXT, entryId = entry.id))
                        Text(field.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        SelectionContainer {
                            Text("{{character.${field.key}}}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
