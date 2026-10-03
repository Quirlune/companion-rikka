package me.rerere.prompt

/** Public, read-only explanation of how one event would be admitted. */
data class EventPreview(
    val eventId: String,
    val rootId: String,
    val configurationRevision: Long,
    val selectedRules: List<PlannedRule>,
    val excludedRules: List<ExcludedRule>,
    val issues: List<RuntimeProblem>,
    val availableQueueSlots: Int,
) {
    val valid: Boolean
        get() = issues.none { it.severity == ProblemSeverity.ERROR }
}

data class PlannedRule(
    val ruleId: String,
    val actionId: String,
    val output: ReferenceId,
    val dependencies: Set<String>,
    val actionAvailable: Boolean,
    val outputEnabled: Boolean,
)

data class ExcludedRule(val ruleId: String, val reason: String)

internal data class EventDispatchPlan(
    val event: RuntimeEvent,
    val state: RuntimeState,
    val selectedRules: List<EventRule>,
    val executableRules: List<EventRule>,
    val excludedRules: List<ExcludedRule>,
    val ancestors: Map<String, Set<String>>,
    val actions: Map<String, TaskAction>,
    val registeredActionIds: Set<String>,
    val triggerArtifacts: List<Artifact>,
    val issues: List<RuntimeProblem>,
    val queueSlots: Int,
)

/** Pure planner shared by preview and dispatch. It never reserves tickets or invokes actions. */
internal object EventPlan {
    fun create(
        event: RuntimeEvent,
        state: RuntimeState,
        activeTasks: Int,
        maxDepth: Int,
        maxPending: Int,
        actions: Map<String, TaskAction>,
        alreadySeen: Boolean,
    ): EventDispatchPlan {
        val issues = mutableListOf<RuntimeProblem>()
        val excluded = mutableListOf<ExcludedRule>()
        if (alreadySeen)
            issues += issue(ProblemCode.DUPLICATE_EVENT, "Event was already dispatched")
        if (event.visitedRules.size >= maxDepth)
            issues += issue(ProblemCode.DEPTH_LIMIT, "Event causal depth limit reached")
        if (event.order < 0 || event.order > state.sequence)
            issues +=
                issue(ProblemCode.INVALID_EVENT_ORDER, "Event order is not reserved by this store")

        val matching = state.rules.filter { it.eventType == event.type }
        state.rules
            .filter { it.eventType != event.type }
            .forEach { rule ->
                excluded += ExcludedRule(rule.id, "Event type does not match")
            }
        matching.forEach { rule ->
            val reason =
                when {
                    !rule.enabled -> "Rule is disabled"
                    rule.id in event.visitedRules -> "Rule already visited in this causal chain"
                    rule.assistantId != null && rule.assistantId != event.scope.assistantId ->
                        "Assistant does not match"
                    !rule.condition.matches(event) -> "Condition did not match"
                    else -> null
                }
            if (reason != null) excluded += ExcludedRule(rule.id, reason)
        }
        val selected = matching.filter { rule -> excluded.none { it.ruleId == rule.id } }
        val byId = selected.associateBy { it.id }
        selected
            .groupBy { it.output }
            .filterValues { it.size > 1 }
            .forEach { (output, writers) ->
                issues +=
                    issue(
                        ProblemCode.COMPETING_WRITERS,
                        "Multiple selected rules write $output",
                        "rules.${writers.joinToString(",") { it.id }}.output",
                    )
            }
        var ancestors = emptyMap<String, Set<String>>()
        try {
            ancestors = validate(selected)
        } catch (error: IllegalArgumentException) {
            issues +=
                issue(ProblemCode.INVALID_PLAN, error.message ?: "Invalid event dependency plan")
        }

        // Disabled output providers are omitted as before. Their dependent joins remain visible
        // and are skipped at execution because the prerequisite has no outcome artifact.
        val enabledProviderIds =
            state.providers.filter { it.enabled }.mapTo(mutableSetOf()) { it.id }
        val definitions = state.definitions.associateBy { it.id }
        val executable = selected.filter { rule ->
            val definition = definitions[rule.output]
            if (definition == null || definition.staticText != null) {
                issues +=
                    issue(
                        ProblemCode.INVALID_PLAN,
                        "Rule output is not a registered dynamic reference: ${rule.output}",
                        "rules.${rule.id}.output",
                    )
                false
            } else rule.output.provider in enabledProviderIds
        }
        val executableIds = executable.mapTo(mutableSetOf()) { it.id }
        selected
            .filter { it.id !in executableIds && definitions[it.output] != null }
            .forEach { rule ->
                if (rule.output.provider !in enabledProviderIds) {
                    issues +=
                        issue(
                            ProblemCode.DISABLED_OUTPUT,
                            "Output provider is disabled: ${rule.output.provider}",
                            "rules.${rule.id}.output",
                            ProblemSeverity.WARNING,
                        )
                }
            }
        selected.forEach { rule ->
            rule.dependsOn
                .filter { it !in byId }
                .forEach { id ->
                    issues +=
                        issue(
                            ProblemCode.MISSING_DEPENDENCY,
                            "Dependency did not match this event: $id",
                            "rules.${rule.id}.dependsOn.$id",
                        )
                }
        }
        val actionSnapshot =
            executable.mapNotNull { rule -> actions[rule.actionId]?.let { rule.id to it } }.toMap()
        executable
            .filter { it.actionId !in actions }
            .forEach { rule ->
                issues +=
                    issue(
                        ProblemCode.UNKNOWN_ACTION,
                        "Unknown action: ${rule.actionId}",
                        "rules.${rule.id}.actionId",
                    )
            }
        val slots = (maxPending - activeTasks).coerceAtLeast(0)
        if (executable.size > slots) {
            issues +=
                issue(
                    ProblemCode.QUEUE_REJECTED,
                    "Background queue capacity exceeded",
                )
        }
        val catalog = ArtifactCatalog(state)
        val triggerArtifacts =
            event.inputArtifacts.distinct().mapNotNull { id ->
                val artifact = catalog.byId[id]
                val issueMessage =
                    when {
                        artifact == null -> "Missing input artifact: $id"
                        artifact.ticket.scope != event.scope ||
                            !artifact.isApplicableTo(event.sources) ->
                            "Input artifact does not belong to this event context: $id"
                        !catalog.isUsable(artifact, requireCurrent = true) ->
                            "Input artifact is no longer usable: $id"
                        else -> null
                    }
                if (issueMessage != null) {
                    issues +=
                        issue(
                            ProblemCode.REFERENCE_UNAVAILABLE,
                            issueMessage,
                            "event.inputArtifacts.$id",
                        )
                    null
                } else artifact
            }
        return EventDispatchPlan(
            event,
            state,
            selected,
            executable,
            excluded,
            ancestors,
            actionSnapshot,
            actions.keys.toSet(),
            triggerArtifacts,
            issues,
            slots,
        )
    }

    fun validate(rules: List<EventRule>): Map<String, Set<String>> {
        val byId = rules.associateBy { it.id }
        require(byId.size == rules.size) { "Duplicate rule IDs" }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        val ancestors = mutableMapOf<String, Set<String>>()
        fun visit(id: String) {
            if (id in visited) return
            require(visiting.add(id)) { "Cyclic task dependency at $id" }
            val rule = requireNotNull(byId[id]) { "Dependency did not match this event: $id" }
            rule.dependsOn.forEach(::visit)
            ancestors[id] = rule.dependsOn + rule.dependsOn.flatMap { ancestors.getValue(it) }
            visiting.remove(id)
            visited.add(id)
        }
        rules.forEach { visit(it.id) }
        return ancestors
    }

    private fun issue(
        code: ProblemCode,
        message: String,
        target: String? = null,
        severity: ProblemSeverity = ProblemSeverity.ERROR,
    ) = RuntimeProblem(code, severity, message, target)
}
