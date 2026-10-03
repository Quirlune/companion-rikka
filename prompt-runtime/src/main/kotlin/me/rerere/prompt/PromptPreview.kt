package me.rerere.prompt

/** A side-effect-free compiler result with local diagnostics for unresolved inputs. */
data class PromptPreview(
    val snapshot: PromptSnapshot?,
    val issues: List<RuntimeProblem>,
) {
    val valid: Boolean
        get() = issues.none { it.severity == ProblemSeverity.ERROR }
}
