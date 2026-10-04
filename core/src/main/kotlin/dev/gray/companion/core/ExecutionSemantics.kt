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
    val bodyDisplayTemplates: Boolean get() = version >= 4
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

    fun isReservedEvent(event: String): Boolean =
        if (legacyProcessing) event in RuntimeEvents.reserved else RuntimeEvents.isReserved(event)

    companion object {
        const val CURRENT = 8
        fun supports(version: Int): Boolean = version in 1..CURRENT
    }
}
