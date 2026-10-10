package me.rerere.rikkahub.ui.components.message

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import me.rerere.ai.core.MessageRole
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.hugeicons.stroke.ArrowRight01
import me.rerere.hugeicons.stroke.Edit01
import me.rerere.hugeicons.stroke.GitFork
import me.rerere.hugeicons.stroke.MoreVertical
import me.rerere.rikkahub.data.model.MessageNode

/** A single gutter button expands left without changing the message layout. */
@Composable
internal fun CompanionMessageToolbar(
    node: MessageNode,
    expanded: Boolean,
    enabled: Boolean,
    branchSelectionEnabled: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onUpdate: (MessageNode) -> Unit,
    onRegenerate: () -> Unit,
    onEdit: () -> Unit,
    onFork: () -> Unit,
) {
    LaunchedEffect(enabled, node.currentMessage.id) {
        if (expanded) onExpandedChange(false)
    }
    Box(Modifier.size(48.dp)) {
        IconButton(
            enabled = enabled,
            onClick = { onExpandedChange(true) },
            modifier = Modifier.size(48.dp),
        ) { Icon(HugeIcons.MoreVertical, "消息操作", Modifier.size(20.dp)) }
        if (expanded && enabled) Popup(
            alignment = Alignment.BottomEnd,
            onDismissRequest = { onExpandedChange(false) },
            properties = PopupProperties(focusable = true),
        ) {
            Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 4.dp, shadowElevation = 4.dp) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        enabled = enabled && branchSelectionEnabled && node.messages.size > 1,
                        onClick = {
                            onExpandedChange(false)
                            onUpdate(node.copy(selectIndex = if (node.selectIndex == 0) node.messages.lastIndex else node.selectIndex - 1))
                        },
                        modifier = Modifier.size(48.dp),
                    ) { Icon(HugeIcons.ArrowLeft01, "上一个版本", Modifier.size(20.dp)) }
                    Text(
                        text = "${node.selectIndex + 1}/${node.messages.size}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.widthIn(min = 32.dp),
                        maxLines = 1,
                    )
                    val canRoll = node.currentMessage.role == MessageRole.ASSISTANT
                    IconButton(
                        enabled = enabled && branchSelectionEnabled && (node.selectIndex < node.messages.lastIndex || canRoll),
                        onClick = {
                            onExpandedChange(false)
                            if (node.selectIndex < node.messages.lastIndex) onUpdate(node.copy(selectIndex = node.selectIndex + 1))
                            else if (canRoll) onRegenerate()
                        },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(HugeIcons.ArrowRight01, if (node.selectIndex == node.messages.lastIndex && canRoll) "生成新候选" else "下一个版本", Modifier.size(20.dp))
                    }
                    IconButton(
                        enabled = enabled,
                        onClick = { onExpandedChange(false); onEdit() },
                        modifier = Modifier.size(48.dp),
                    ) { Icon(HugeIcons.Edit01, "编辑", Modifier.size(20.dp)) }
                    IconButton(
                        enabled = enabled,
                        onClick = { onExpandedChange(false); onFork() },
                        modifier = Modifier.size(48.dp),
                    ) { Icon(HugeIcons.GitFork, "创建分支", Modifier.size(20.dp)) }
                    IconButton(
                        onClick = { onExpandedChange(false) },
                        modifier = Modifier.size(48.dp),
                    ) { Icon(HugeIcons.MoreVertical, "收起消息操作", Modifier.size(20.dp)) }
                }
            }
        }
    }
}
