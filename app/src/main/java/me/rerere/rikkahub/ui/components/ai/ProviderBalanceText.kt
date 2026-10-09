package me.rerere.rikkahub.ui.components.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.TextButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import me.rerere.rikkahub.ui.pages.setting.components.ProviderModelLoadPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import me.rerere.ai.provider.ProviderManager
import me.rerere.ai.provider.ProviderSetting
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MoneyBag02
import me.rerere.rikkahub.utils.SimpleCache
import me.rerere.rikkahub.utils.toDp
import org.koin.compose.koinInject
import java.util.concurrent.TimeUnit

private val cache = SimpleCache.builder<String, String>()
    .expireAfterWrite(2, TimeUnit.MINUTES)
    .build()

@Composable
fun ProviderBalanceText(
    providerSetting: ProviderSetting,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    loadPolicy: ProviderModelLoadPolicy = ProviderModelLoadPolicy.AUTOMATIC,
    providerManagerOverride: ProviderManager? = null,
    onRequestError: ((Throwable) -> Throwable)? = null,
    enabled: Boolean = true,
) {
    if (!providerSetting.balanceOption.enabled || providerSetting !is ProviderSetting.OpenAI) {
        // Balance option is disabled or provider is not OpenAI type
        return
    }

    if (loadPolicy == ProviderModelLoadPolicy.DISABLED) return
    val providerManager = providerManagerOverride ?: koinInject<ProviderManager>()
    val scope = rememberCoroutineScope()
    var manualValue by remember(providerSetting) { mutableStateOf("~") }
    var loading by remember(providerSetting) { mutableStateOf(false) }

    val value = if (loadPolicy == ProviderModelLoadPolicy.AUTOMATIC) produceState(initialValue = "~", key1 = providerSetting.id, key2 = providerSetting.balanceOption) {
        // Check cache first
        val cachedBalance = cache.getIfPresent("${providerSetting.id},${providerSetting.balanceOption.hashCode()}")
        if (cachedBalance != null) {
            value = cachedBalance
        } else {
            // Fetch balance from API
            runCatching {
                val balance = providerManager.getProviderByType(providerSetting).getBalance(providerSetting)
                // Cache the result
                cache.put("${providerSetting.id},${providerSetting.balanceOption.hashCode()}", balance)
                value = balance
            }.onFailure {
                // Handle error
                if (it is CancellationException) throw it
                val errorMsg = "Error: ${(onRequestError?.invoke(it) ?: it).message}"
                // Don't cache error messages
                value = errorMsg
            }
        }
    } else null

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = HugeIcons.MoneyBag02,
            contentDescription = null,
            modifier = Modifier.size(style.fontSize.toDp()),
            tint = color.takeOrElse { LocalContentColor.current }
        )
        Text(
            text = value?.value ?: manualValue,
            style = style,
            maxLines = 1,
            color = color
        )
        if (loadPolicy == ProviderModelLoadPolicy.MANUAL) TextButton(
            enabled = enabled && !loading,
            onClick = {
                loading = true
                scope.launch {
                    try {
                        manualValue = providerManager.getProviderByType(providerSetting).getBalance(providerSetting)
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        manualValue = (onRequestError?.invoke(failure) ?: failure).message ?: "查询失败"
                    } finally { loading = false }
                }
            }
        ) { Text(if (loading) "查询中…" else "查询余额") }
    }
}
