package me.rerere.rikkahub.ui.pages.extensions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.model.QuickMessage
import kotlin.uuid.Uuid

class QuickMessagesVM(
    private val settingsStore: SettingsStore
) : ViewModel() {
    private val updateMutex = Mutex()
    val settings = settingsStore.settingsFlow
        .stateIn(viewModelScope, SharingStarted.Lazily, Settings.dummy())

    fun addQuickMessage(title: String, content: String) {
        updateQuickMessages { quickMessages ->
            quickMessages + QuickMessage(
                title = title,
                content = content,
            )
        }
    }

    fun updateQuickMessage(updated: QuickMessage) {
        updateQuickMessages { quickMessages ->
            quickMessages.map { quickMessage ->
                if (quickMessage.id == updated.id) updated else quickMessage
            }
        }
    }

    fun deleteQuickMessage(id: Uuid) {
        updateQuickMessages { quickMessages ->
            quickMessages.filterNot { quickMessage ->
                quickMessage.id == id
            }
        }
    }

    private fun updateQuickMessages(transform: (List<QuickMessage>) -> List<QuickMessage>) {
        viewModelScope.launch {
            updateMutex.withLock {
                settingsStore.update { settings ->
                    settings.copy(quickMessages = transform(settings.quickMessages))
                }
            }
        }
    }
}
