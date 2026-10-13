package me.rerere.rikkahub.ui.context

import androidx.compose.runtime.staticCompositionLocalOf
import com.dokar.sonner.ToasterState
import com.dokar.sonner.Toast
import com.dokar.sonner.ToastType
import dev.gray.companion.AppEventLog
import dev.gray.companion.AppLogLevel
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import java.util.UUID

/** Route passive notices to the Companion log, retaining real action-bearing toasts. */
class UiToaster(private val delegate: ToasterState, private val logNotices: Boolean = false) {
    fun show(message: Any, id: Any = UUID.randomUUID(), icon: Any? = null, action: Any? = null,
             type: ToastType = ToastType.Normal, duration: Duration = 3.seconds): Toast {
        val toast = Toast(message = message, id = id, icon = icon, action = action, type = type, duration = duration)
        show(toast)
        return toast
    }

    fun show(toast: Toast) {
        if (logNotices) AppEventLog.record("界面", (toast.message as? CharSequence)?.toString() ?: "界面通知", when (toast.type) {
            ToastType.Error -> AppLogLevel.ERROR
            ToastType.Warning -> AppLogLevel.WARNING
            else -> AppLogLevel.INFO
        })
        if (!logNotices || toast.action != null) delegate.show(toast)
    }

    fun dismiss(toast: Toast) = delegate.dismiss(toast)
    fun dismiss(id: Any) = delegate.dismiss(id)
    fun dismissAll() = delegate.dismissAll()
}

val LocalToaster = staticCompositionLocalOf<UiToaster> { error("Not provided") }
