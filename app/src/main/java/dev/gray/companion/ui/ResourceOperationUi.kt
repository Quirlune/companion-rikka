package dev.gray.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.*

internal data class ResourceOperationPreview(
    val target: ResourceLocation,
    val name: String,
    val deleting: Boolean,
    val plan: ResourceMutationPlan,
    val unlinkAssociations: Boolean = false,
)

/** These rows describe declared uses, not inferred runtime dependency ownership. */
@Composable
internal fun ResourceUsageRows(usages: List<ResourceUse>, onOpenLocation: (ResourceLocation) -> Unit) {
    usages.forEach { use ->
        val kind = when (use.kind) {
            ResourceUseKind.BINDING -> "资源关联"
            ResourceUseKind.EXTENSION -> "扩展"
            ResourceUseKind.TEMPLATE -> "模板引用"
            ResourceUseKind.SESSION -> "对话"
            ResourceUseKind.SAVED_CHAT -> "已保存对话"
            ResourceUseKind.RECOVERY -> "恢复来源"
            ResourceUseKind.ACCEPTED_WORLD -> "已接受的历史状态"
        }
        val label = "$kind · ${use.label}"
        val location = use.location
        if (location != null) TextButton(onClick = { onOpenLocation(location) }, contentPadding = PaddingValues(0.dp)) {
            Text(label, modifier = Modifier.fillMaxWidth())
        } else Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun ResourceOperationSheet(
    library: Library,
    preview: ResourceOperationPreview,
    busy: Boolean,
    onApply: (((Library) -> ExtensionEditResult) -> Unit)?,
    onOpenLocation: (ResourceLocation) -> Unit,
    onChange: (ResourceOperationPreview) -> Unit,
    onDismiss: () -> Unit,
) {
    var submitted by remember(preview) { mutableStateOf(false) }
    val plan = preview.plan
    LaunchedEffect(library, submitted, busy) {
        if (submitted && !busy) {
            val finished = if (preview.deleting) !resourceExists(library, preview.target.resource)
                else plan.destination?.let { resourceExists(library, it.resource) } == true
            if (finished) onDismiss()
        }
    }
    fun refresh(unlink: Boolean = preview.unlinkAssociations) {
        onChange(preview.copy(unlinkAssociations = unlink, plan = if (preview.deleting)
            ResourceEditCommands.previewDelete(library, preview.target, removeConfiguredAssociations = unlink)
        else ResourceEditCommands.previewCopy(library, preview.target)))
    }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }) {
        Column(Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("${if (preview.deleting) "删除" else "复制"}「${preview.name.ifBlank { "未命名" }}」", style = MaterialTheme.typography.titleLarge)
            if (plan.changes.isNotEmpty()) {
                Text("将进行的修改", style = MaterialTheme.typography.titleMedium)
                plan.changes.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            if (plan.usages.isNotEmpty()) {
                Text(if (preview.deleting) "使用位置" else "原资源的使用位置", style = MaterialTheme.typography.titleMedium)
                ResourceUsageRows(plan.usages) { location -> if (!busy) onOpenLocation(location) }
            }
            if (preview.deleting && (preview.unlinkAssociations || plan.usages.any { it.removableAssociation })) {
                ToggleLine("同时解除可移除的配置关联", preview.unlinkAssociations) { if (!busy) refresh(it) }
                Text("仅处理上列资源绑定与扩展组成；对话、已保存对话和恢复来源须保留。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (plan.issues.isNotEmpty()) {
                Text("需要先处理", style = MaterialTheme.typography.titleMedium)
                plan.issues.forEach { issue ->
                    val location = ResourceLocations.resolveIssue(library, issue)
                    if (location == null) Text(issue.message, color = MaterialTheme.colorScheme.error)
                    else TextButton(enabled = !busy, onClick = { onOpenLocation(location) }, contentPadding = PaddingValues(0.dp)) {
                        Text(issue.message, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            if (onApply == null) Text("此入口暂不能提交资源操作。", color = MaterialTheme.colorScheme.error)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy, onClick = onDismiss) { Text("取消") }
                TextButton(enabled = !busy, onClick = { refresh() }) { Text("刷新预览") }
                Spacer(Modifier.weight(1f))
                Button(enabled = !busy && plan.canApply && onApply != null, onClick = {
                    submitted = true
                    onApply?.invoke { latest ->
                        val result = ResourceEditCommands.apply(plan, latest)
                        ExtensionEditResult(result.library, result.issues, plan.changes, receipt = result.receipt)
                    }
                }) { Text(if (preview.deleting) "确认删除" else "确认复制") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun resourceExists(library: Library, ref: ExtensionResourceRef): Boolean = when (ref.kind) {
    ExtensionResourceKind.CHARACTER -> library.characters.any { it.id == ref.id }
    ExtensionResourceKind.MAIN -> library.mainCalls.any { it.id == ref.id }
    ExtensionResourceKind.PACKAGE -> library.packages.any { it.id == ref.id }
    ExtensionResourceKind.GROUP -> library.groups.any { it.id == ref.id }
}
