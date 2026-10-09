package dev.gray.companion.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.gray.companion.core.AvatarImage
import dev.gray.companion.core.CharacterCard
import dev.gray.companion.AvatarImportPreparation
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun CharacterAvatarBadge(card: CharacterCard?, size: Dp = 36.dp) {
    // Decode only the displayed card, off the UI thread; unchanged avatars reuse the result.
    val bitmap by produceState<Bitmap?>(null, card?.avatar) {
        value = withContext(Dispatchers.Default) {
            card?.avatar?.decodedBytes()?.let { bytes ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 256) sample *= 2
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    }
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.size(size)) {
        val image = bitmap
        if (image != null) Image(image.asImageBitmap(), contentDescription = "${card?.name.orEmpty()}的头像", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().clip(CircleShape))
        else Box(contentAlignment = Alignment.Center) {
            Text(card?.name?.trim()?.take(1)?.ifEmpty { "角" } ?: "角", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
internal fun CharacterAvatarEditor(card: CharacterCard, onProcessingChange: (Boolean) -> Unit, onChange: (AvatarImage?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val choose = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            loading = true
            onProcessingChange(true)
            error = null
            scope.launch {
                try { onChange(withContext(Dispatchers.IO) { loadAvatar(context, uri) }) }
                catch (_: Exception) { error = "无法读取图片，请选择 10 MB 以内的常见图片。原头像仍保留。" }
                finally { loading = false; onProcessingChange(false) }
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CharacterAvatarBadge(card, 64.dp)
            TextButton(onClick = { choose.launch("image/*") }, enabled = !loading) { Text(if (loading) "正在处理…" else "选择头像") }
            if (card.avatar != null) TextButton(onClick = { error = null; onChange(null) }, enabled = !loading) { Text("移除") }
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
