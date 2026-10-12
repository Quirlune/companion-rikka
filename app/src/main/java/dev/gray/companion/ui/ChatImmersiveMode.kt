package dev.gray.companion.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Chat-only immersive mode; edge gestures reveal system bars and navigation restores prior visibility. */
@Composable
internal fun ChatImmersiveMode() {
    val view = LocalView.current
    DisposableEffect(view) {
        var context: Context? = view.context
        while (context is ContextWrapper && context !is Activity) context = context.baseContext
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        val previous = ViewCompat.getRootWindowInsets(view)
        val statusWasVisible = previous?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
        val navigationWasVisible = previous?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true
        val behavior = controller?.systemBarsBehavior
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            if (statusWasVisible) controller?.show(WindowInsetsCompat.Type.statusBars())
            if (navigationWasVisible) controller?.show(WindowInsetsCompat.Type.navigationBars())
            if (behavior != null) controller?.systemBarsBehavior = behavior
        }
    }
}
