package me.rerere.prompt

/** Stable codes for local developer diagnostics; messages may contain sensitive action errors. */
enum class ProblemSeverity {
    ERROR,
    WARNING,
}

enum class ProblemCode {
    CONFIGURATION_CONFLICT,
    INVALID_CONFIGURATION,
    MISSING_REFERENCE,
    MISSING_DEPENDENCY,
    HISTORICAL_REFERENCE,
    NAMESPACE_CONFLICT,
    STORAGE_FAILURE,
    INVALID_EVENT_ORDER,
    DUPLICATE_EVENT,
    DEPTH_LIMIT,
    INVALID_PLAN,
    COMPETING_WRITERS,
    DISABLED_OUTPUT,
    UNKNOWN_ACTION,
    QUEUE_REJECTED,
    DEPENDENCY_UNAVAILABLE,
    PUBLICATION_OBSOLETE,
    ACTION_FAILED,
    TIMEOUT,
    CANCELLED,
    UNKNOWN_BUILTIN,
    REFERENCE_UNAVAILABLE,
}

data class RuntimeProblem(
    val code: ProblemCode,
    val severity: ProblemSeverity,
    val message: String,
    val targetPath: String? = null,
)

/** Invalid API input remains compatible with callers checking IllegalArgumentException. */
class RuntimeOperationException(
    val problem: RuntimeProblem,
    cause: Throwable? = null,
) : IllegalArgumentException(problem.message, cause)
