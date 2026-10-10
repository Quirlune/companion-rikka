package dev.gray.companion.core

internal data class PlannedNode(val workflow: Workflow, val node: WorkflowNode, val action: RegisteredAction?) {
    val key: String get() = "${workflow.id}/${node.id}"
    val priority: Int get() = if (workflow.queued) workflow.priority else 0
}

internal data class EventPlan(
    val trigger: String,
    /** Original topology remains the locator/enumeration authority and the <=14 driver. */
    val waves: List<List<PlannedNode>>,
    val parallelWaves: List<List<PlannedNode>> = emptyList(),
    val queuedWaves: List<List<PlannedNode>> = emptyList()
)

internal data class RunPlan(val events: Map<String, EventPlan>) {
    /** Required preparation writers only; ordinary models must not wait for unrelated queue work. */
    fun preparationDependencies(library: Library, session: Session, main: MainCall): Map<String, Set<String>> {
        val preparation = events[RuntimeEvents.TURN_PREPARE]?.waves?.flatten().orEmpty()
        if (preparation.isEmpty()) return emptyMap()
        val all = events.values.flatMap { it.waves.flatten() }
        val actions = all.mapNotNull { ref -> ref.action?.let { ref.node.actionId to it } }.toMap()
        val models = main.workflows.filter { it.enabled }.flatMap { workflow ->
            workflow.nodes.filter { it.enabled && PluginParticipation.nodeActive(library, session, it) &&
                actions[it.actionId]?.descriptor?.usesModel == true }
                .map { "${workflow.id}/${it.id}" }
        }.toSet()
        if (models.isEmpty()) return emptyMap()
        val owners = main.configuredWorkflows().associate { it.id to main.id } +
            library.loadedCallGroups(session, main).flatMap { group -> group.workflows.map { it.id to group.id } }.toMap()
        val refs = preparation.associateBy { it.key }
        fun activePath(ref: PlannedNode, seen: MutableSet<String> = mutableSetOf()): Boolean {
            if (!seen.add(ref.key)) return false
            if (!ref.workflow.enabled || !ref.node.enabled || ref.workflow.id !in owners ||
                !MemoryPlugins.workflowActive(library, session, ref.workflow.id) ||
                !PluginParticipation.nodeActive(library, session, ref.node)) return false
            return ref.node.dependsOn.all { id -> refs["${ref.workflow.id}/$id"]?.let {
                activePath(it, seen.toMutableSet())
            } == true }
        }
        fun prerequisites(ref: PlannedNode): Set<String> = buildSet {
            fun visit(key: String) {
                if (!add(key)) return
                refs[key]?.let { parent -> parent.node.dependsOn.forEach { visit("${parent.workflow.id}/$it") } }
            }
            visit(ref.key)
        }
        data class Writer(val ref: PlannedNode, val owner: String?, val id: String, val dynamicId: Boolean,
                          val targets: Set<String>)
        fun dynamic(value: String): Boolean = value.contains("{{")
        val producers = preparation.mapNotNull { ref ->
            val descriptor = ref.action?.descriptor ?: return@mapNotNull null
            val idField = descriptor.injectionIdField ?: return@mapNotNull null
            if (!activePath(ref) || descriptor.removesInjection) return@mapNotNull null
            val arguments = descriptor.withDefaults(ref.node.arguments)
            val id = arguments[idField].orEmpty().trim()
            val workflow = descriptor.injectionTargetWorkflowField?.let(arguments::get)?.trim().orEmpty()
            val node = descriptor.injectionTargetNodeField?.let(arguments::get)?.trim().orEmpty()
            // Patch names and slot validity cannot change the model that needs this writer.
            // In particular, a disabled/missing slot must fail before that model is requested.
            // Runtime validates the actual slot after its prerequisite input is available.
            val targets = if (listOf(workflow, node).any(::dynamic)) {
                // A guessed dynamic target must not invert a producer's real model ancestors.
                models - prerequisites(ref)
            } else setOfNotNull(injectionTarget(main, actions, workflow, node)?.key).intersect(models)
            Writer(ref, owners[ref.workflow.id], id, dynamic(id), targets)
        }
        val dependencies = mutableMapOf<String, MutableSet<String>>()
        fun include(ref: PlannedNode, targets: Set<String>) {
            val required = prerequisites(ref)
            targets.forEach { dependencies.getOrPut(it) { linkedSetOf() }.addAll(required) }
        }
        producers.forEach { include(it.ref, it.targets) }
        val currentUserMessageId = session.messages.lastOrNull {
            it.role == Role.USER && it.outputSlot == OutputSlot.BODY
        }?.id
        for (ref in preparation) {
            val descriptor = ref.action?.descriptor ?: continue
            if (!activePath(ref) || !descriptor.removesInjection) continue
            val idField = descriptor.injectionIdField ?: continue
            val id = descriptor.withDefaults(ref.node.arguments)[idField].orEmpty().trim()
            val owner = owners[ref.workflow.id]
            val targets = if (dynamic(id)) models else buildSet {
                session.promptInjections.filter { it.id == id && owner != null && it.origin.resourceId == owner &&
                    (it.scope == InjectionScope.SESSION || it.turnId == currentUserMessageId) &&
                    PluginParticipation.injectionActive(library, session, it.pluginOwnerIds, it.origin.resourceId) }.forEach {
                    injectionTarget(main, actions, it.targetWorkflowId, it.targetNodeId, it.targetBlockId)?.key
                        ?.takeIf { key -> key in models }?.let(::add)
                }
                // A newly prepared injection may not yet exist in the accepted session.
                producers.filter { owner != null && it.owner == owner && (it.id == id || it.dynamicId) }
                    .forEach { addAll(it.targets) }
            }
            include(ref, targets)
        }
        return dependencies.mapValues { it.value.toSet() }
    }

    companion object {
        fun create(workflows: List<Workflow>, availableActions: Map<String, RegisteredAction>, executionVersion: Int = ExecutionSemantics.CURRENT): Pair<RunPlan?, Issue?> {
            val semantics = ExecutionSemantics(executionVersion)
            val events = linkedMapOf<String, EventPlan>()
            val categories = mutableMapOf<String, String>()
            for (workflow in workflows.filter { it.enabled }) for (node in workflow.nodes.filter { it.enabled }) {
                val target = node.declaredWriteAddress(availableActions[node.actionId]?.descriptor)
                    ?.takeIf { it.startsWith("result.") }?.substringAfter("result.")
                for (key in (listOfNotNull(node.outputKey, target) + listOfNotNull(
                    node.regexOutputCopy.outputKey.takeIf { node.regexOutputCopy.enabled })).distinct()) {
                    val previous = categories.putIfAbsent(key, node.resultCategory)
                    if (previous != null && previous != node.resultCategory)
                        return null to Issue("workflow.${workflow.id}.node.${node.id}", "同一个输出宏须使用相同分类")
                }
            }
            for (workflow in workflows.filter { it.enabled }) {
                if (semantics.workflowCompletionEvents && workflow.effectiveTrigger() == RuntimeEvents.workflowCompleted(workflow.id))
                    return null to Issue("workflow.${workflow.id}", "工作流不能监听自身完成事件")
            }
            for ((trigger, eventWorkflows) in workflows.filter { it.enabled }.groupBy { it.effectiveTrigger() }) {
                val nodes = mutableListOf<PlannedNode>()
                for (workflow in eventWorkflows) {
                    for (node in workflow.nodes) {
                        val action = availableActions[node.actionId]
                        if (node.enabled && action == null) return null to Issue("workflow.${workflow.id}.node.${node.id}", "未注册的动作：${node.actionId}")
                        if (node.enabled) {
                            action!!.descriptor.executionIssue()?.let {
                                return null to Issue("workflow.${workflow.id}.node.${node.id}", it)
                            }
                            val issue = action.descriptor.validate(node.arguments, "workflow.${workflow.id}.node.${node.id}").firstOrNull { it.error }
                            if (issue != null) return null to issue
                            val eventName = node.arguments["event"].orEmpty().trim()
                            if (node.actionId == "event.emit" && semantics.isReservedEvent(eventName)) {
                                return null to Issue("workflow.${workflow.id}.node.${node.id}.argument.event", "此事件由运行时派发")
                            }
                        }
                        nodes += PlannedNode(workflow, node, action)
                    }
                }
                val (original, originalIssue) = buildWaves(nodes, trigger, respectPriority = true)
                if (originalIssue != null) return null to originalIssue
                if (!semantics.independentEventLanes) {
                    events[trigger] = EventPlan(trigger, original!!.waves)
                    continue
                }
                val (parallel, parallelIssue) = buildWaves(nodes.filter { !it.workflow.queued }, trigger, respectPriority = false)
                if (parallelIssue != null) return null to parallelIssue
                val (queued, queuedIssue) = buildWaves(nodes.filter { it.workflow.queued }, trigger, respectPriority = true)
                if (queuedIssue != null) return null to queuedIssue
                // Lanes have no relative completion order, including nodes in later lane waves.
                // Reject guaranteed collisions up front; conditional actual writes are actor-checked.
                val parallelWriters = nodes.filter { it.key in parallel!!.guaranteed }.flatMap { ref ->
                    ref.node.declaredOutputAddresses(ref.action?.descriptor).map { it to ref.workflow.id }
                }.groupBy({ it.first }, { it.second })
                // Each ordinary workflow advances independently, so its later waves may overlap
                // a peer's earlier waves. Only one workflow's own dependency order is sequential.
                if (parallelWriters.values.any { it.toSet().size > 1 })
                    return null to Issue("event.$trigger", "普通并行调用写入同一宏地址")
                val parallelWrites = parallelWriters.keys
                val guaranteedQueued = nodes.filter { it.key in queued!!.guaranteed }
                // Queue levels are sequential; workflows within one level independently advance.
                if (guaranteedQueued.groupBy { it.priority }.values.any { cohort ->
                        cohort.flatMap { ref -> ref.node.declaredOutputAddresses(ref.action?.descriptor)
                            .map { it to ref.workflow.id } }
                            .groupBy({ it.first }, { it.second }).values.any { it.toSet().size > 1 }
                    }) return null to Issue("event.$trigger", "同级排队调用写入同一宏地址")
                val queuedWrites = guaranteedQueued.flatMap {
                    it.node.declaredOutputAddresses(it.action?.descriptor)
                }.toSet()
                if (parallelWrites.any { it in queuedWrites })
                    return null to Issue("event.$trigger", "普通与排队调用写入同一宏地址")
                events[trigger] = EventPlan(trigger, original!!.waves, parallel!!.waves, queued!!.waves)
            }
            return RunPlan(events.toMap()) to null
        }

        private data class PlannedWaves(val waves: List<List<PlannedNode>>, val guaranteed: Set<String>)

        private fun buildWaves(nodes: List<PlannedNode>, trigger: String,
                               respectPriority: Boolean): Pair<PlannedWaves?, Issue?> {
            val pending = nodes.toMutableList()
            val waves = mutableListOf<List<PlannedNode>>()
            val planned = mutableSetOf<String>()
            val unconditional = mutableSetOf<String>()
            while (pending.isNotEmpty()) {
                val priority = if (respectPriority) pending.minOf { it.priority } else 0
                val ready = pending.filter { ref -> (!respectPriority || ref.priority == priority) &&
                    ref.node.dependsOn.all { "${ref.workflow.id}/$it" in planned } }
                if (ready.isEmpty()) return null to Issue("event.$trigger", "工作流依赖无法完成")
                // A conditional or disabled ancestor can skip its entire dependent path.
                val guaranteed = ready.filter { ref ->
                    ref.workflow.condition == null && ref.workflow.recognition == null &&
                        ref.node.enabled && ref.node.condition == null && ref.node.recognition == null &&
                        ref.node.dependsOn.all { "${ref.workflow.id}/$it" in unconditional }
                }
                val addresses = guaranteed.flatMap { it.node.declaredOutputAddresses(it.action?.descriptor) }
                if (addresses.size != addresses.toSet().size)
                    return null to Issue("event.$trigger", "并行节点写入同一宏地址")
                waves += ready
                planned += ready.map { it.key }
                unconditional += guaranteed.map { it.key }
                pending.removeAll(ready.toSet())
            }
            return PlannedWaves(waves, unconditional) to null
        }
    }
}
