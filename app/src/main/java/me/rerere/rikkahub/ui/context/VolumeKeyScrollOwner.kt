package me.rerere.rikkahub.ui.context

import android.view.KeyEvent

/** The original chat list's activity key-listener contract. */
interface VolumeKeyScrollOwner {
    val volumeKeyListeners: MutableList<(isVolumeUp: Boolean) -> Boolean>
}

internal fun VolumeKeyScrollOwner.consumeVolumeKey(event: KeyEvent): Boolean {
    if (event.action != KeyEvent.ACTION_DOWN) return false
    val isVolumeUp = when (event.keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> true
        KeyEvent.KEYCODE_VOLUME_DOWN -> false
        else -> return false
    }
    return volumeKeyListeners.lastOrNull()?.invoke(isVolumeUp) == true
}
