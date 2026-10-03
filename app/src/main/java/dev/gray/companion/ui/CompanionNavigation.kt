package dev.gray.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.CharacterCard
import dev.gray.companion.core.UserIdentity
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.ArrowLeft01
import me.rerere.rikkahub.ui.components.ui.CardGroup
import me.rerere.rikkahub.ui.components.ui.UIAvatar
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.ui.theme.CustomColors

@Composable
internal fun SettingsHeader(title: String, onBack: () -> Unit) {
    LargeFlexibleTopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = { CompanionBackButton(onClick = onBack) },
        colors = CustomColors.topBarColors,
    )
}

@Composable
internal fun CompanionBackButton(onClick: () -> Unit, enabled: Boolean = true) {
    FilledTonalIconButton(
        onClick = onClick,
        enabled = enabled,
        shapes = IconButtonDefaults.shapes(),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = CustomColors.listItemColors.containerColor,
        ),
    ) {
        Icon(HugeIcons.ArrowLeft01, contentDescription = "返回")
    }
}

@Composable
internal fun UserIdentityEditor(initial: UserIdentity, onSave: (UserIdentity) -> Unit, onBack: () -> Unit, saving: Boolean) {
    var item by remember { mutableStateOf(initial) }
    var avatarBusy by remember { mutableStateOf(false) }
    var avatarEditorOpen by remember { mutableStateOf(false) }
    EditorPage("用户资料", onBack, { onSave(item.copy(name = item.name.trim().ifBlank { "用户" })) }, dirty = item != initial, saving = saving || avatarBusy) {
        TextFieldLine("昵称", item.name, { item = item.copy(name = it) })
        CardGroup {
            item(
                onClick = { avatarEditorOpen = !avatarEditorOpen },
                leadingContent = {
                    UIAvatar(
                        name = item.name,
                        value = item.avatar?.let { Avatar.Image("data:${it.mediaType};base64,${it.base64}") } ?: Avatar.Dummy,
                        onClick = { avatarEditorOpen = !avatarEditorOpen },
                        modifier = Modifier.size(48.dp),
                    )
                },
                headlineContent = { Text("头像") },
                supportingContent = { Text("点击更换或移除头像") },
            )
        }
        if (avatarEditorOpen) CharacterAvatarEditor(CharacterCard(id = "local-user", name = item.name, avatar = item.avatar), { avatarBusy = it }) { item = item.copy(avatar = it) }
    }
}
