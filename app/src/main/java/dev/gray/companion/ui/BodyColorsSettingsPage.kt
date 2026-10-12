package dev.gray.companion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.rerere.rikkahub.data.datastore.DisplaySetting
import me.rerere.rikkahub.ui.context.LocalSettings
import me.rerere.rikkahub.ui.pages.setting.SettingPageLayout
import me.rerere.rikkahub.ui.pages.setting.SettingVM
import org.koin.androidx.compose.koinViewModel

@Composable
internal fun BodyColorsSettingsPage(vm: SettingVM = koinViewModel()) {
    SettingPageLayout(title = "正文颜色 · 全局显示偏好") {
        item { BodyColorsSettingsContent(Modifier.padding(16.dp), vm) }
    }
}

@Composable
internal fun BodyColorsSettingsContent(modifier: Modifier = Modifier, vm: SettingVM = koinViewModel()) {
    val saved by vm.settings.collectAsStateWithLifecycle()
    var display by remember(saved) { mutableStateOf(saved.displaySetting) }
    fun update(value: DisplaySetting) {
        display = value
        vm.updateSettings(saved.copy(displaySetting = value))
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("正文默认支持 Markdown。颜色是全局显示偏好，修改立即保存并应用于所有聊天，不随主调用模板保存或撤销。", style = MaterialTheme.typography.bodyMedium)
        BodyColorField("粗体 · **文字**", display.companionBoldColor) { update(display.copy(companionBoldColor = it)) }
        BodyColorField("斜体 · *文字*", display.companionItalicColor) { update(display.copy(companionItalicColor = it)) }
        BodyColorField("引号 · “文字” / \"文字\"", display.companionQuoteColor) { update(display.copy(companionQuoteColor = it)) }
        TextButton(onClick = {
            val defaults = DisplaySetting()
            update(display.copy(companionBoldColor = defaults.companionBoldColor,
                companionItalicColor = defaults.companionItalicColor, companionQuoteColor = defaults.companionQuoteColor))
        }) { Text("恢复默认颜色") }
        Text("预览", style = MaterialTheme.typography.titleSmall)
        CompositionLocalProvider(LocalSettings provides saved.copy(displaySetting = display)) {
            BodyMarkdownBlock("普通正文，**粗体强调**，*斜体描写*。\n\n“我在这里。” 她说：\"慢慢来。\"\n\n`代码中的 \"引号\" 保持原样`")
        }
        Text("引号颜色优先于粗体和斜体；代码不染色。仅改变显示，不改变正文、历史或发送内容。自定义正文外观会先解析 {{正文}} 的 Markdown，再应用外层 HTML/CSS；模板可用更具体的 CSS 覆盖默认颜色。", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BodyColorField(label: String, value: String, onChange: (String) -> Unit) {
    var input by remember(value) { mutableStateOf(value) }
    val valid = Regex("#[0-9a-fA-F]{6}").matches(input)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = input, onValueChange = {
            input = it
            if (Regex("#[0-9a-fA-F]{6}").matches(it)) onChange(it)
        }, label = { Text(label) }, supportingText = { Text(if (valid) "色值格式：#RRGGBB" else "请输入完整的六位色值，例如 #767676") },
            isError = !valid, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("#767676", "#A0712F", "#B97758", "#8C82B5", "#64969A", "#657F60").forEach { color ->
                Box(Modifier.size(36.dp).clip(CircleShape).background(bodyColor(color, "#767676"))
                    .clickable { input = color; onChange(color) })
            }
        }
    }
}
