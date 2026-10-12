package me.rerere.rikkahub

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable

sealed interface Screen : NavKey {
    @Serializable data class CompanionResources(val kind: Int) : Screen
    @Serializable data class CompanionEditor(val kind: String, val id: String, val focus: dev.gray.companion.core.ResourceLocation? = null, val visitId: String = "") : Screen
    @Serializable data class CompanionExtensions(val mainCallId: String = "") : Screen
    @Serializable data class CompanionRecoveryPrompt(val sessionId: String, val runId: String, val nodeKey: String) : Screen
    @Serializable data object CompanionSession : Screen
    @Serializable data object CompanionChatDisplay : Screen
    @Serializable data object CompanionConfigurationWorkspace : Screen
    @Serializable data object CompanionBodyColors : Screen
    @Serializable data object CompanionAutomaticOptions : Screen
    @Serializable data class CompanionMemory(val visitId: String = "", val focus: dev.gray.companion.core.ResourceLocation? = null) : Screen
    @Serializable data object CompanionReasoning : Screen
    @Serializable data object CompanionImportHistory : Screen
    @Serializable data object CompanionPersonas : Screen
    @Serializable data object CompanionUsage : Screen
    @Serializable data object CompanionTags : Screen
    @Serializable data object CompanionPromptPreview : Screen
    @Serializable
    data class Chat(
        val id: String,
        val text: String? = null,
        val files: List<String> = emptyList(),
        val nodeId: String? = null
    ) : Screen

    @Serializable
    data class ShareHandler(val text: String, val streamUri: String? = null) : Screen

    @Serializable
    data object History : Screen

    @Serializable
    data object Favorite : Screen

    @Serializable
    data object Assistant : Screen

    @Serializable
    data class AssistantDetail(val id: String) : Screen

    @Serializable
    data class AssistantBasic(val id: String) : Screen

    @Serializable
    data class AssistantPrompt(val id: String) : Screen

    @Serializable
    data class AssistantMemory(val id: String) : Screen

    @Serializable
    data class AssistantRequest(val id: String) : Screen

    @Serializable
    data class AssistantMcp(val id: String) : Screen

    @Serializable
    data class AssistantLocalTool(val id: String) : Screen

    @Serializable
    data class AssistantInjections(val id: String) : Screen

    @Serializable
    data object Translator : Screen

    @Serializable
    data object Setting : Screen

    @Serializable
    data object Backup : Screen

    @Serializable
    data object ImageGen : Screen

    @Serializable
    data class WebView(val url: String = "", val contentId: String = "") : Screen

    @Serializable
    data object SettingTheme : Screen

    @Serializable
    data object SettingPreferences : Screen

    @Serializable
    data object SettingPreferencesTheme : Screen

    @Serializable
    data object SettingPreferencesNotification : Screen

    @Serializable
    data object SettingPreferencesGeneral : Screen

    @Serializable
    data object SettingPreferencesUI : Screen

    @Serializable
    data object SettingPreferencesNetwork : Screen

    @Serializable
    data object SettingProvider : Screen

    @Serializable
    data class SettingProviderDetail(val providerId: String) : Screen

    @Serializable
    data object SettingModels : Screen

    @Serializable
    data object SettingAbout : Screen

    @Serializable
    data object SettingSearch : Screen

    @Serializable
    data class SettingSearchDetail(val serviceId: String) : Screen

    @Serializable
    data object SettingSpeech : Screen

    @Serializable
    data object SettingMcp : Screen

    @Serializable
    data object SettingDonate : Screen

    @Serializable
    data object SettingFiles : Screen

    @Serializable
    data object SettingWeb : Screen

    @Serializable
    data object Debug : Screen

    @Serializable
    data object Log : Screen

    @Serializable
    data object Extensions : Screen

    @Serializable
    data object QuickMessages : Screen

    @Serializable
    data object Prompts : Screen

    @Serializable
    data object Skills : Screen

    @Serializable
    data object Workspaces : Screen

    @Serializable
    data class WorkspaceDetail(val id: String) : Screen

    @Serializable
    data class WorkspaceTerminal(val id: String) : Screen

    @Serializable
    data class WorkspaceFileEditor(val id: String, val area: String, val path: String) : Screen

    @Serializable
    data class SkillDetail(val skillName: String) : Screen

    @Serializable
    data object MessageSearch : Screen

    @Serializable
    data object Stats : Screen
}

/** Native navigation transitions and state decorators reused by Companion screens. */
@Composable
internal fun RikkaNavDisplay(
    backStack: List<NavKey>,
    onBack: () -> Unit,
    entryProvider: (NavKey) -> NavEntry<NavKey>,
) {
    NavDisplay(
        backStack = backStack,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        modifier = Modifier.fillMaxSize(),
        onBack = onBack,
        transitionSpec = {
            if (backStack.size == 1) fadeIn() togetherWith fadeOut()
            else {
                slideInHorizontally { it } togetherWith
                    slideOutHorizontally { -it / 2 } + scaleOut(targetScale = 0.7f) + fadeOut()
            }
        },
        popTransitionSpec = {
            slideInHorizontally { -it / 2 } + scaleIn(initialScale = 0.7f) + fadeIn() togetherWith
                slideOutHorizontally { it }
        },
        predictivePopTransitionSpec = {
            slideInHorizontally { -it / 2 } + scaleIn(initialScale = 0.7f) + fadeIn() togetherWith
                slideOutHorizontally { it }
        },
        entryProvider = entryProvider,
    )
}

internal fun rikkaChatTransitions() =
    NavDisplay.transitionSpec { fadeIn() togetherWith fadeOut() } +
        NavDisplay.popTransitionSpec { fadeIn() togetherWith fadeOut() }
