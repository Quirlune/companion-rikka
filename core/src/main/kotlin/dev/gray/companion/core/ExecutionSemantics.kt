package dev.gray.companion.core

/**
 * Interpretation of a captured execution version. This is never persisted: journals keep their
 * original integer and old default. Independent capabilities may evolve separately in later runs.
 */
data class ExecutionSemantics(val version: Int) {
    val legacyProcessing: Boolean get() = version == 1
    val workflowCompletionEvents: Boolean get() = version >= 2
    val capturedMacroTargets: Boolean get() = version >= 3
    val currentOnlyMacros: Boolean get() = version >= 3
    val retainModelResults: Boolean get() = version >= 4
    val bodyDisplayTemplates: Boolean get() = version in 4..18
    val legacyTiles: Boolean get() = version <= 18
    val historySources: Boolean get() = version >= 5
    val staticResourceQueries: Boolean get() = version >= 5
    val allValidSummaryMarks: Boolean get() = version >= 5
    val reasoningArchives: Boolean get() = version >= 6
    val provisionalProcessedOutput: Boolean get() = version >= 6
    val hierarchicalMemory: Boolean get() = version >= 7
    val omitEmptyReferencesBeforeProcessing: Boolean get() = version >= 7
    val preserveInputWithoutTemplate: Boolean get() = version >= 7
    val localHistoryFormats: Boolean get() = version >= 8
    val quoteAwareTemplates: Boolean get() = version >= 8
    val explicitHistoryRanges: Boolean get() = version >= 8
    val completedPureReplay: Boolean get() = version >= 9
    val bodyOnlyArchives: Boolean get() = version >= 9

    fun isReservedEvent(event: String): Boolean =
        if (legacyProcessing) event in RuntimeEvents.reserved else RuntimeEvents.isReserved(event)

    val anchoredPromptSlots: Boolean get() = version >= 10

    val independentContextOrder: Boolean get() = version >= 11

    val historySourceRoleSelection: Boolean get() = version >= 12
    val mergeAdjacentModelRoles: Boolean get() = version >= 12
    val nativeMemoryPlugins: Boolean get() = version >= 13
    val earlyDefaultBodyPublication: Boolean get() = version >= 14
    val independentEventLanes: Boolean get() = version >= 15
    val primaryReplyCompletion: Boolean get() = version >= 15
    val streamingProcessedOutput: Boolean get() = version >= 16
    val floorBoundCardSources: Boolean get() = version >= 16

    val globalMemory: Boolean get() = version >= 17
    val scopedMemoryResources: Boolean get() = version >= 18
    val persistDisplayReasoning: Boolean get() = version >= 21

    val validatedMemoryXml: Boolean get() = version >= 23
    val globalExtensionSwitches: Boolean get() = version >= 23

    /** Call parameters belong to the selected behavior; legacy captures keep profile defaults. */
    val behaviorOnlyGeneration: Boolean get() = version >= 24
    val userInputMacros: Boolean get() = version >= 25
    val perBlockMessageMerging: Boolean get() = version >= 26

    val pluginHostCapabilities: Boolean get() = version >= 27

    /** Older captures always recorded a model's own result, ignoring its trajectory switch. */
    val modelTrajectoryControl: Boolean get() = version >= 28

    companion object {
        const val CURRENT = 28
        fun supports(version: Int): Boolean = version in 1..CURRENT
    }
}
