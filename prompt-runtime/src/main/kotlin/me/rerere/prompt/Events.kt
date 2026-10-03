package me.rerere.prompt

import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RuntimeEvent(
    val type: String,
    val scope: ContextScope,
    val sources: List<SourceVersion>,
    val data: Map<String, String> = emptyMap(),
    val id: String = UUID.randomUUID().toString(),
    val rootId: String = id,
    val visitedRules: Set<String> = emptySet(),
    val order: Long = 0,
    val inputArtifacts: List<String> = emptyList(),
) {
    init {
        require(type.isNotBlank() && id.isNotBlank() && rootId.isNotBlank())
        require(order >= 0)
        requireUniqueSources(sources)
    }
}

@Serializable
sealed interface EventCondition {
    fun matches(event: RuntimeEvent): Boolean

    @Serializable
    @SerialName("always")
    data object Always : EventCondition {
        override fun matches(event: RuntimeEvent) = true
    }

    @Serializable
    @SerialName("equals")
    data class Equals(val key: String, val value: String) : EventCondition {
        override fun matches(event: RuntimeEvent) = event.data[key] == value
    }

    @Serializable
    @SerialName("all")
    data class All(val conditions: List<EventCondition>) : EventCondition {
        override fun matches(event: RuntimeEvent) = conditions.all { it.matches(event) }
    }

    @Serializable
    @SerialName("any")
    data class Any(val conditions: List<EventCondition>) : EventCondition {
        override fun matches(event: RuntimeEvent) = conditions.any { it.matches(event) }
    }

    @Serializable
    @SerialName("not")
    data class Not(val condition: EventCondition) : EventCondition {
        override fun matches(event: RuntimeEvent) = !condition.matches(event)
    }
}

@Serializable
data class EventRule(
    val id: String,
    val eventType: String,
    val actionId: String,
    val output: ReferenceId,
    val condition: EventCondition = EventCondition.Always,
    val arguments: Map<String, String> = emptyMap(),
    val assistantId: String? = null,
    val enabled: Boolean = true,
    val timeoutMillis: Long = 120_000,
    /** Rule IDs from the same event dispatch, never latest results from another event. */
    val dependsOn: Set<String> = emptySet(),
) {
    init {
        require(id.isNotBlank() && eventType.isNotBlank() && actionId.isNotBlank())
        require(timeoutMillis > 0)
        require(id !in dependsOn && dependsOn.none { it.isBlank() })
    }
}

data class TaskInput(
    val event: RuntimeEvent,
    val rule: EventRule,
    val dependencies: Map<String, Artifact> = emptyMap(),
    val triggerArtifacts: List<Artifact> = emptyList(),
)

/** Android/model integration supplies actions; the engine knows no model or UI classes. */
fun interface TaskAction {
    suspend fun execute(input: TaskInput): String
}

enum class TaskStatus {
    QUEUED,
    WAITING,
    RUNNING,
    SUCCEEDED,
    SUPERSEDED,
    FAILED,
    CANCELLED,
    SKIPPED;

    val isTerminal: Boolean
        get() = this != QUEUED && this != WAITING && this != RUNNING
}

data class TaskRecord(
    val id: String,
    val eventId: String,
    val ruleId: String,
    val scope: ContextScope,
    val status: TaskStatus,
    val error: String? = null,
    val artifactId: String? = null,
    val rootId: String = eventId,
    val queuedAtMillis: Long = 0,
    val startedAtMillis: Long? = null,
    val finishedAtMillis: Long? = null,
    /**
     * Monotonic execution through publication; excludes queue, dependency wait and downstream
     * dispatch.
     */
    val elapsedExecutionMillis: Long? = null,
    val attemptCount: Int = 0,
    val problem: RuntimeProblem? = null,
)

data class DispatchProblem(
    val eventId: String,
    val rootId: String,
    val problem: RuntimeProblem,
    val occurredAtMillis: Long = System.currentTimeMillis(),
)
