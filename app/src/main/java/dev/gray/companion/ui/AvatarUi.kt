package dev.gray.companion.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.AvatarImage
import dev.gray.companion.core.CharacterCard
import dev.gray.companion.AvatarImportPreparation
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.ui.components.ui.UIAvatar

@Composable
internal fun CharacterAvatarBadge(card: CharacterCard?, size: Dp = 36.dp) {
    ResourceAvatarBadge(card?.name.orEmpty().ifBlank { "角色" }, card?.avatar, size)
}

@Composable
internal fun ResourceAvatarBadge(name: String, avatar: AvatarImage?, size: Dp = 36.dp, onClick: (() -> Unit)? = null) {
    val original = remember(avatar) { avatar?.let { Avatar.Image("data:${it.mediaType};base64,${it.base64}") } ?: Avatar.Dummy }
    UIAvatar(name = name, value = original, modifier = Modifier.size(size), onClick = onClick)
}

@Composable
internal fun CharacterAvatarEditor(card: CharacterCard, onProcessingChange: (Boolean) -> Unit, onChange: (AvatarImage?) -> Unit) {
    ResourceAvatarEditor(card.name, card.avatar, onProcessingChange, onChange)
}

@Composable
internal fun ResourceAvatarEditor(name: String, avatar: AvatarImage?, onProcessingChange: (Boolean) -> Unit, onChange: (AvatarImage?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val change by rememberUpdatedState(onChange)
    val processingChange by rememberUpdatedState(onProcessingChange)
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            loading = true
            processingChange(true)
            error = null
            scope.launch {
                try { change(withContext(Dispatchers.IO) { loadAvatar(context, uri) }) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "无法读取图片，请选择 10 MB 以内的常见图片。原头像仍保留。" }
                finally { loading = false; processingChange(false) }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ResourceAvatarBadge(name, avatar, 64.dp, onClick = { if (!loading) choose.launch("image/*") })
            TextButton(onClick = { choose.launch("image/*") }, enabled = !loading) { Text(if (loading) "正在处理…" else "选择头像") }
            if (avatar != null) TextButton(onClick = { error = null; change(null) }, enabled = !loading) { Text("移除") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Copy a small square thumbnail now; no persisted URI permission or original image is needed. */
private fun loadAvatar(context: Context, uri: Uri): AvatarImage {
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var count: Int
        while (input.read(buffer).also { count = it } != -1) {
            require(output.size() + count <= 10 * 1024 * 1024)
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    } ?: error("图片不可读")
    return AvatarImportPreparation.encode(bytes)
}
