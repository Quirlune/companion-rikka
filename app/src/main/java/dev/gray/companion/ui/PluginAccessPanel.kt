package dev.gray.companion.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.gray.companion.PluginAccessStatus
import dev.gray.companion.core.ConfigurationExtension
import dev.gray.companion.core.Issue

/** Local approvals and credential values are managed separately from the extension enable switch. */
@Composable
internal fun PluginAccessPanel(
    definition: ConfigurationExtension,
    busy: Boolean,
    pluginAccessStatus: (ConfigurationExtension) -> PluginAccessStatus,
    onGrantPluginAccess: (ConfigurationExtension) -> List<Issue>,
    onRevokePluginAccess: (String) -> List<Issue>,
    onSetPluginCredential: (ConfigurationExtension, String, String) -> List<Issue>,
) {
    val code = definition.code ?: return
    var refreshKey by remember(definition.id) { mutableIntStateOf(0) }
    val status = remember(definition, refreshKey) { pluginAccessStatus(definition) }
    var working by remember(definition.id) { mutableStateOf(false) }
    var errors by remember(definition.id) { mutableStateOf<List<Issue>>(emptyList()) }
    // Deliberately use ordinary composition state, never rememberSaveable for secret input.
    val drafts = remember(definition.id) { mutableStateMapOf<String, String>() }
    DisposableEffect(drafts) { onDispose { drafts.clear() } }
    val declaredAliases = remember(code.permissions) {
        code.permissions.flatMap { it.credentials }.filter(String::isNotBlank).distinct()
    }
    val enabled = !busy && !working

    fun perform(allowWhileBusy: Boolean = false, action: () -> List<Issue>) {
        if (working || (busy && !allowWhileBusy)) return
        working = true
        errors = try { action() } catch (_: Throwable) {
            listOf(Issue("plugin.access", "操作失败，请重试。"))
        }
        working = false
        if (errors.isEmpty()) refreshKey++
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("插件访问权限", style = MaterialTheme.typography.titleMedium)
        if (code.version != 2) {
            Text("JavaScript v1 只运行本地转换代码，没有联网或宿主能力；不会申请本机权限。若代码仍有能力声明，需切到 v2 或在编辑页清除后才能保存。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("代码声明的能力（声明不等于授权）", style = MaterialTheme.typography.labelLarge)
            if (code.permissions.isEmpty()) Text("没有声明网络能力，HTTP 不可用；capabilities.describe 与 runtime.sleep 仍可调用，无需网络授权。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            else code.permissions.forEachIndexed { index, permission ->
                val summary = buildString {
                    append(permission.capability)
                    if (permission.origins.isNotEmpty()) append(" · 源站：${permission.origins.joinToString()}")
                    if (permission.methods.isNotEmpty()) append(" · 方法：${permission.methods.joinToString()}")
                    if (permission.credentials.isNotEmpty()) append(" · 凭据别名：${permission.credentials.joinToString()}")
                }
                Text("${index + 1}. $summary", style = MaterialTheme.typography.bodySmall)
            }

            val hasSavedGrant = status.fingerprint.isNotBlank()
            val accessLabel = when {
                status.granted -> "已授权"
                hasSavedGrant -> "代码更改后须重新授权"
                else -> "未授权"
            }
            Text("本机授权：$accessLabel", style = MaterialTheme.typography.bodyMedium,
                color = if (status.granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = enabled && code.permissions.isNotEmpty(), onClick = {
                    perform { onGrantPluginAccess(definition) }
                }) { Text("授权当前声明") }
                OutlinedButton(enabled = !working && hasSavedGrant, onClick = {
                    perform(allowWhileBusy = true) { onRevokePluginAccess(definition.id) }
                }) { Text("撤销授权") }
            }

            if (declaredAliases.isNotEmpty()) {
                Text("凭据", style = MaterialTheme.typography.titleSmall)
                Text("只输入并写入凭据，不会读取或回填已保存的值。插件只能按已声明的别名引用凭据。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                declaredAliases.forEach { alias ->
                    key(alias) {
                        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("$alias · ${if (alias in status.configuredCredentials) "已配置" else "未配置"}",
                                style = MaterialTheme.typography.bodyMedium)
                            OutlinedTextField(value = drafts[alias].orEmpty(),
                                onValueChange = { drafts[alias] = it },
                                enabled = enabled,
                                label = { Text("输入凭据") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth())
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                OutlinedButton(enabled = enabled && alias in status.configuredCredentials,
                                    onClick = {
                                        perform { onSetPluginCredential(definition, alias, "") }
                                        if (errors.isEmpty()) drafts.remove(alias)
                                    }) {
                                    Text("清除")
                                }
                                Button(enabled = enabled && !drafts[alias].isNullOrBlank(), onClick = {
                                    val secret = drafts[alias].orEmpty()
                                    perform { onSetPluginCredential(definition, alias, secret) }
                                    if (errors.isEmpty()) drafts.remove(alias)
                                }) { Text("保存凭据") }
                            }
                        }
                    }
                }
            }
        }
        errors.forEach { issue ->
            Text(issue.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}
