package dev.gray.companion

import dev.gray.companion.core.ConfigurationWorkspace
import dev.gray.companion.core.Issue

/** Controller result shared by the human workbench and future in-process agent tools. */
data class WorkspaceOperationResult(
    val workspace: ConfigurationWorkspace? = null,
    val status: String = "OK",
    val issues: List<Issue> = emptyList(),
    val retryable: Boolean = false,
)
