package dev.gray.companion.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.gray.companion.agent.AgentController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.io.ByteArrayOutputStream

/** Kept by AgentPage while switching tabs so an unsaved edit survives UI navigation. */
internal class AgentFileDraft {
    var directory by mutableStateOf("")
    var path by mutableStateOf("")
    var text by mutableStateOf("")
    var savedText by mutableStateOf("")
    var hash by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var entries by mutableStateOf<List<Pair<String, Boolean>>>(emptyList())
    var message by mutableStateOf("")
    var importText by mutableStateOf<String?>(null)
    var importPath by mutableStateOf("")
    var importHash by mutableStateOf<String?>(null)
    val dirty: Boolean get() = text != savedText
    val hasPendingWork: Boolean get() = dirty || importText != null || busy
}

@Composable
internal fun rememberAgentFileDrafts() = remember { mutableMapOf<String, AgentFileDraft>() }

@Composable
internal fun AgentFilePage(
    agent: AgentController,
    sessionId: String?,
    draft: AgentFileDraft?,
    scope: CoroutineScope,
    onNotice: (String) -> Unit,
) {
    if (sessionId == null || draft == null) {
        Text("请先新建 Agent 聊天", Modifier.padding(16.dp))
        return
    }
    val context = LocalContext.current
    var newPath by remember(sessionId) { mutableStateOf("") }
    var movePath by remember(sessionId) { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmMove by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf<(() -> Unit)?>(null) }
    var confirmOverwrite by remember { mutableStateOf<String?>(null) }

    fun runFile(operation: String, args: JsonObject, onSuccess: (JsonObject) -> Unit = {}) {
        if (draft.busy) return
        scope.launch {
            draft.busy = true
            try {
                val reply = agent.files(operation, args)
                if (reply.agentString("status") == "OK") {
                    val data = reply["data"] as? JsonObject ?: JsonObject(emptyMap())
                    draft.busy = false
                    onSuccess(data)
                    draft.message = "操作已完成"
                } else draft.message = reply.agentString("message") ?: "${reply.agentString("status") ?: "FAILED"}：操作未完成"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { draft.message = "文件操作未完成，请检查后重试。" }
            finally { draft.busy = false }
        }
    }
    fun list(path: String = draft.directory) {
        runFile("file.list", buildJsonObject { put("path", path) }) { data ->
            draft.directory = path
            draft.entries = (data["entries"] as? JsonArray).orEmpty().mapNotNull { raw ->
                val entry = raw as? JsonObject ?: return@mapNotNull null
                val name = entry.agentString("name") ?: return@mapNotNull null
                name to ((entry["directory"] as? JsonPrimitive)?.content == "true")
            }
        }
    }
    fun open(path: String) {
        runFile("file.read", buildJsonObject { put("path", path) }) { data ->
            draft.path = path
            draft.text = data.agentString("text").orEmpty()
            draft.savedText = draft.text
            draft.hash = data.agentString("hash")
        }
    }
    fun closeOrOpen(action: () -> Unit) {
        if (draft.dirty) confirmDiscard = action else action()
    }
    fun save(path: String, value: String, expected: String?, clearImport: Boolean = false) {
        runFile("file.write", buildJsonObject {
            put("path", path); put("text", value)
            if (expected == null) put("expectedHash", JsonNull) else put("expectedHash", expected)
        }) { data ->
            if (!clearImport || path == draft.path) {
                draft.path = path
                draft.text = value
                draft.savedText = value
                draft.hash = data.agentString("hash")
            }
            draft.importText = null
            confirmOverwrite = null
            if (clearImport) draft.importPath = ""
            list(draft.directory)
        }
    }

    val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            draft.importText = readAgentImport(context, uri)
            draft.importPath = uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "import.txt"
            draft.importHash = null
        } catch (_: Exception) { draft.importText = null; onNotice("只能导入不超过 256 KiB 的 UTF-8 文本。") }
    }
    val createDocument = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                output.write(draft.text.toByteArray(StandardCharsets.UTF_8))
            } ?: error("输出不可用")
            onNotice("文本已导出")
        } catch (_: Exception) { onNotice("导出未完成。") }
    }

    LaunchedEffect(sessionId) { list("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { if (draft.directory.isNotEmpty()) list(draft.directory.substringBeforeLast('/', "")) },
                enabled = draft.directory.isNotEmpty() && !draft.busy) { Text("上一级") }
            TextButton(onClick = { list() }, enabled = !draft.busy) { Text("刷新") }
            TextButton(onClick = { openDocument.launch(arrayOf("text/plain", "text/*")) }) { Text("导入文本") }
        }
        Text("/${draft.directory}", style = MaterialTheme.typography.labelMedium)
        if (draft.message.isNotBlank()) Text(draft.message, style = MaterialTheme.typography.bodySmall,
            color = if (draft.message == "操作已完成") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        draft.entries.forEach { (name, directory) ->
            val path = listOf(draft.directory, name).filter { it.isNotEmpty() }.joinToString("/")
            OutlinedCard(onClick = { closeOrOpen { if (directory) list(path) else open(path) } }, modifier = Modifier.fillMaxWidth()) {
                Text((if (directory) "文件夹 · " else "文本 · ") + name, Modifier.padding(12.dp))
            }
        }
        HorizontalDivider()
        Text("编辑文件", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(newPath, { newPath = it }, label = { Text("新文件相对路径") },
            modifier = Modifier.fillMaxWidth(), singleLine = true)
        OutlinedButton(onClick = {
            val path = newPath.trim()
            if (path.isNotEmpty()) closeOrOpen {
                draft.path = path; draft.text = ""; draft.savedText = ""; draft.hash = null; newPath = ""
            }
        }, enabled = newPath.isNotBlank() && !draft.busy) { Text("新建文本") }
        if (draft.path.isNotBlank()) {
            Text("${draft.path}${if (draft.dirty) " · 未保存" else ""}", style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(draft.text, { draft.text = it }, label = { Text("UTF-8 内容") },
                modifier = Modifier.fillMaxWidth(), minLines = 8)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { save(draft.path, draft.text, draft.hash) }, enabled = !draft.busy && (draft.dirty || draft.hash == null)) { Text("保存") }
                OutlinedButton(onClick = { createDocument.launch(draft.path.substringAfterLast('/')) }, enabled = !draft.busy) { Text("导出") }
                TextButton(onClick = { closeOrOpen { draft.path = ""; draft.text = ""; draft.savedText = ""; draft.hash = null } }) { Text("关闭") }
            }
            if (draft.hash != null) {
                OutlinedTextField(movePath, { movePath = it }, label = { Text("移动到相对路径") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { confirmMove = true }, enabled = !draft.busy && !draft.dirty && movePath.isNotBlank()) { Text("移动…") }
                    TextButton(onClick = { confirmDelete = true }, enabled = !draft.busy) { Text("删除") }
                }
            }
        }
        draft.importText?.let { text ->
            HorizontalDivider()
            Text("导入文本", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(draft.importPath, { draft.importPath = it }, label = { Text("写入新相对路径") },
                modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { save(draft.importPath.trim(), text, null, clearImport = true) }, enabled = !draft.busy && draft.importPath.isNotBlank()) { Text("作为新文件写入") }
                TextButton(onClick = { draft.importText = null }) { Text("取消") }
            }
            if (draft.path.isNotBlank() && draft.hash != null) {
                TextButton(onClick = { draft.importHash = draft.hash; confirmOverwrite = draft.path }) { Text("覆盖当前文件…") }
            }
        }
    }

    if (confirmDiscard != null) AlertDialog(onDismissRequest = { confirmDiscard = null },
        title = { Text("放弃未保存的内容？") }, text = { Text("当前文件的编辑尚未保存。") },
        confirmButton = { TextButton(onClick = { val action = confirmDiscard; confirmDiscard = null; action?.invoke() }) { Text("放弃并继续") } },
        dismissButton = { TextButton(onClick = { confirmDiscard = null }) { Text("继续编辑") } })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("删除文件？") }, text = { Text("${draft.path} 将从这场聊天的文件区移除。") },
        confirmButton = { TextButton(onClick = {
            confirmDelete = false
            val path = draft.path; val hash = draft.hash ?: return@TextButton
            runFile("file.delete", buildJsonObject { put("path", path); put("expectedHash", hash) }) {
                draft.path = ""; draft.text = ""; draft.savedText = ""; draft.hash = null; list(draft.directory)
            }
        }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
    if (confirmMove) AlertDialog(onDismissRequest = { confirmMove = false },
        title = { Text("移动文件？") }, text = { Text("${draft.path} → ${movePath.trim()}") },
        confirmButton = { TextButton(onClick = {
            confirmMove = false
            val source = draft.path; val destination = movePath.trim(); val hash = draft.hash ?: return@TextButton
            runFile("file.move", buildJsonObject {
                put("from", source); put("to", destination); put("expectedHash", hash)
            }) { data -> draft.path = data.agentString("to").orEmpty(); movePath = ""; list(draft.directory) }
        }) { Text("移动") } },
        dismissButton = { TextButton(onClick = { confirmMove = false }) { Text("取消") } })
    if (confirmOverwrite != null) AlertDialog(onDismissRequest = { confirmOverwrite = null },
        title = { Text("覆盖当前文件？") }, text = { Text("将用导入文本覆盖 ${confirmOverwrite.orEmpty()}，并核对读取时的文件 hash。") },
        confirmButton = { TextButton(onClick = {
            val path = confirmOverwrite ?: return@TextButton
            val text = draft.importText ?: return@TextButton
            val expected = draft.importHash ?: return@TextButton
            confirmOverwrite = null
            save(path, text, expected, clearImport = true)
        }) { Text("覆盖") } },
        dismissButton = { TextButton(onClick = { confirmOverwrite = null }) { Text("取消") } })
}

private fun JsonObject.agentString(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun readAgentImport(context: Context, uri: Uri): String {
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val count = input.read(chunk)
            if (count < 0) break
            output.write(chunk, 0, count)
            require(output.size() <= 256 * 1024)
        }
        output.toByteArray()
    } ?: error("输入不可用")
    require(!bytes.contains(0.toByte()))
    return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
}
