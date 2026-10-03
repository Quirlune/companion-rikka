package me.rerere.prompt

/** Commands are data only. A batch is validated after all commands have been applied. */
sealed interface ConfigurationCommand {
    data class RegisterReference(val definition: ReferenceDefinition) : ConfigurationCommand

    data class RemoveReference(val id: ReferenceId) : ConfigurationCommand

    data class ImportPackage(val pack: PromptPackage) : ConfigurationCommand

    data class RemovePackage(val id: String) : ConfigurationCommand

    data class SetPreset(val assistantId: String, val preset: PromptPreset) : ConfigurationCommand

    data class RemovePreset(val assistantId: String) : ConfigurationCommand

    data class PutRule(val rule: EventRule) : ConfigurationCommand

    data class RemoveRule(val id: String) : ConfigurationCommand

    data class SetEnabled(val providerId: String, val enabled: Boolean) : ConfigurationCommand
}

data class ConfigurationSnapshot(
    val configurationRevision: Long,
    val providers: List<ProviderState>,
    val definitions: List<ReferenceDefinition>,
    val packages: List<PromptPackage>,
    val presets: List<PresetBinding>,
    val rules: List<EventRule>,
)

data class ConfigurationPreview(
    /** The candidate carries the current revision; apply again with it as expectedRevision. */
    val candidate: ConfigurationSnapshot,
    val issues: List<RuntimeProblem>,
    val wouldChange: Boolean,
) {
    val valid: Boolean
        get() = issues.none { it.severity == ProblemSeverity.ERROR }
}

data class ConfigurationMutation(
    val snapshot: ConfigurationSnapshot,
    val changed: Boolean,
)
