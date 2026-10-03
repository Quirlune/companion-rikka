package me.rerere.prompt

import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

@Serializable data class PresetBinding(val assistantId: String, val preset: PromptPreset)

@Serializable
data class RuntimeState(
    val schemaVersion: Int = 1,
    val sequence: Long = 0,
    val providers: List<ProviderState> = emptyList(),
    val definitions: List<ReferenceDefinition> = emptyList(),
    val tickets: List<PublicationTicket> = emptyList(),
    val artifacts: List<Artifact> = emptyList(),
    val presets: List<PresetBinding> = emptyList(),
    val rules: List<EventRule> = emptyList(),
    val packages: List<PromptPackage> = emptyList(),
    val configurationRevision: Long = 0,
    val stateRevision: Long = 0,
)

interface RuntimePersistence {
    suspend fun load(): RuntimeState?

    suspend fun save(state: RuntimeState)
}

class MemoryRuntimePersistence : RuntimePersistence {
    private var state: RuntimeState? = null

    override suspend fun load() = state

    override suspend fun save(state: RuntimeState) {
        this.state = state
    }
}

/** Serializes reads and commits; a failed disk commit never advances the in-memory state. */
class RuntimeStore(private val persistence: RuntimePersistence) {
    private val mutex = Mutex()
    private var loaded: RuntimeState? = null
    private val _stateRevisions = MutableStateFlow<Long?>(null)
    val stateRevisions: StateFlow<Long?> = _stateRevisions.asStateFlow()

    private suspend fun current(): RuntimeState {
        loaded?.let {
            return it
        }
        val persisted =
            try {
                persistence.load() ?: RuntimeState()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Throwable) {
                throw RuntimeOperationException(
                    RuntimeProblem(
                        ProblemCode.STORAGE_FAILURE,
                        ProblemSeverity.ERROR,
                        "Unable to load runtime state",
                    ),
                    cause,
                )
            }
        RuntimeStateValidator.validate(persisted)
        val detached = detachedState(persisted)
        loaded = detached
        _stateRevisions.value = detached.stateRevision
        return detached
    }

    private suspend fun commit(state: RuntimeState) {
        val previous = loaded ?: current()
        val detached = detachedState(state)
        val configurationChanged = !sameConfiguration(previous, detached)
        val effective =
            detached.copy(
                configurationRevision =
                    previous.configurationRevision + if (configurationChanged) 1 else 0,
                stateRevision = previous.stateRevision + 1,
            )
        if (sameContent(previous, detached)) return
        withContext(NonCancellable) {
            try {
                persistence.save(detachedState(effective))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (cause: Throwable) {
                throw RuntimeOperationException(
                    RuntimeProblem(
                        ProblemCode.STORAGE_FAILURE,
                        ProblemSeverity.ERROR,
                        "Unable to persist runtime state",
                    ),
                    cause,
                )
            }
            loaded = effective
            _stateRevisions.value = effective.stateRevision
        }
    }

    private fun sameConfiguration(a: RuntimeState, b: RuntimeState) =
        a.providers == b.providers &&
            a.definitions == b.definitions &&
            a.packages == b.packages &&
            a.presets == b.presets &&
            a.rules == b.rules

    private fun sameContent(a: RuntimeState, b: RuntimeState) =
        a.copy(configurationRevision = 0, stateRevision = 0) ==
            b.copy(configurationRevision = 0, stateRevision = 0)

    private suspend fun snapshot(state: RuntimeState): ConfigurationSnapshot =
        ConfigurationSnapshot(
            state.configurationRevision,
            state.providers.map { it.copy() },
            state.definitions.map(::detachedDefinition),
            state.packages.map(::detachedPackage),
            state.presets.map(::detachedBinding),
            state.rules.map(::detachedRule),
        )

    suspend fun configuration(): ConfigurationSnapshot = mutex.withLock { snapshot(current()) }

    suspend fun previewConfiguration(
        commands: List<ConfigurationCommand>,
        expectedRevision: Long? = null,
    ): ConfigurationPreview = mutex.withLock {
        val state = current()
        if (expectedRevision != null && expectedRevision != state.configurationRevision) {
            return@withLock ConfigurationPreview(
                snapshot(state),
                listOf(
                    problem(ProblemCode.CONFIGURATION_CONFLICT, "Configuration revision changed")
                ),
                false,
            )
        }
        val candidateResult = applyCommands(state, commands)
        val candidate =
            candidateResult.state.copy(configurationRevision = state.configurationRevision)
        val issues = candidateResult.issues + validateCandidate(candidate)
        ConfigurationPreview(
            snapshot(candidate),
            issues.distinct(),
            !sameConfiguration(state, candidate),
        )
    }

    suspend fun applyConfiguration(
        commands: List<ConfigurationCommand>,
        expectedRevision: Long? = null,
    ): ConfigurationMutation = mutex.withLock {
        val state = current()
        if (expectedRevision != null && expectedRevision != state.configurationRevision) {
            throw operation(ProblemCode.CONFIGURATION_CONFLICT, "Configuration revision changed")
        }
        val result = applyCommands(state, commands)
        val candidate = result.state
        val issues = result.issues + validateCandidate(candidate)
        val error = issues.firstOrNull { it.severity == ProblemSeverity.ERROR }
        if (error != null) throw RuntimeOperationException(error)
        val changed = !sameConfiguration(state, candidate)
        if (changed) commit(candidate)
        ConfigurationMutation(snapshot(loaded ?: state), changed)
    }

    private data class CommandResult(val state: RuntimeState, val issues: List<RuntimeProblem>)

    private fun applyCommands(
        initial: RuntimeState,
        commands: List<ConfigurationCommand>,
    ): CommandResult {
        var state = detachedState(initial)
        val issues = mutableListOf<RuntimeProblem>()
        commands.forEachIndexed { index, command ->
            val path = "commands[$index]"
            when (command) {
                is ConfigurationCommand.RegisterReference -> {
                    val definition = detachedDefinition(command.definition)
                    if (definition.staticText != null)
                        issues +=
                            problem(
                                ProblemCode.NAMESPACE_CONFLICT,
                                "Dynamic registrations cannot be static",
                                "$path.definition",
                            )
                    else {
                        if (state.packages.any { it.id == definition.id.provider })
                            issues +=
                                problem(
                                    ProblemCode.NAMESPACE_CONFLICT,
                                    "Provider namespace belongs to a package",
                                    "$path.definition.id.provider",
                                )
                        val providers =
                            if (state.providers.any { it.id == definition.id.provider })
                                state.providers
                            else state.providers + ProviderState(definition.id.provider)
                        state =
                            state.copy(
                                providers = providers,
                                definitions =
                                    replaceInPlace(state.definitions, definition) { it.id },
                            )
                    }
                }
                is ConfigurationCommand.RemoveReference -> {
                    val historical =
                        state.tickets.any { it.reference == command.id } ||
                            state.artifacts.any { it.ticket.reference == command.id }
                    if (historical)
                        issues +=
                            problem(
                                ProblemCode.HISTORICAL_REFERENCE,
                                "Reference has historical tickets or artifacts",
                                "$path.id",
                            )
                    else
                        state =
                            state.copy(
                                definitions = state.definitions.filterNot { it.id == command.id }
                            )
                }
                is ConfigurationCommand.ImportPackage -> {
                    val pack = detachedPackage(command.pack)
                    val hasDynamicHistory =
                        state.tickets.any { it.reference.provider == pack.id } ||
                            state.artifacts.any { it.ticket.reference.provider == pack.id }
                    if (hasDynamicHistory)
                        issues +=
                            problem(
                                ProblemCode.NAMESPACE_CONFLICT,
                                "A namespace with dynamic history cannot become a package",
                                "$path.pack.id",
                            )
                    else if (
                        state.definitions.any { it.id.provider == pack.id && it.staticText == null }
                    )
                        issues +=
                            problem(
                                ProblemCode.NAMESPACE_CONFLICT,
                                "Package namespace belongs to a dynamic provider",
                                "$path.pack.id",
                            )
                    else {
                        val providers =
                            if (state.providers.any { it.id == pack.id }) state.providers
                            else state.providers + ProviderState(pack.id)
                        val definitions = replacePackageDefinitions(state.definitions, pack)
                        state =
                            state.copy(
                                providers = providers,
                                packages = replaceInPlace(state.packages, pack) { it.id },
                                definitions = definitions,
                            )
                    }
                }
                is ConfigurationCommand.RemovePackage -> {
                    val existing = state.packages.singleOrNull { it.id == command.id }
                    if (existing != null) {
                        state =
                            state.copy(
                                packages = state.packages.filterNot { it.id == command.id },
                                definitions =
                                    state.definitions.filterNot { it.id.provider == command.id },
                                providers =
                                    state.providers.map {
                                        if (it.id == command.id) it.copy(epoch = it.epoch + 1)
                                        else it
                                    },
                            )
                    }
                }
                is ConfigurationCommand.SetPreset -> {
                    val preset = detachedPreset(command.preset)
                    state =
                        state.copy(
                            presets =
                                replaceInPlace(
                                    state.presets,
                                    PresetBinding(command.assistantId, preset),
                                ) {
                                    it.assistantId
                                }
                        )
                }
                is ConfigurationCommand.RemovePreset ->
                    state =
                        state.copy(
                            presets =
                                state.presets.filterNot { it.assistantId == command.assistantId }
                        )
                is ConfigurationCommand.PutRule -> {
                    val rule = detachedRule(command.rule)
                    state = state.copy(rules = replaceInPlace(state.rules, rule) { it.id })
                }
                is ConfigurationCommand.RemoveRule ->
                    state = state.copy(rules = state.rules.filterNot { it.id == command.id })
                is ConfigurationCommand.SetEnabled -> {
                    if (state.providers.none { it.id == command.providerId })
                        issues +=
                            problem(
                                ProblemCode.INVALID_CONFIGURATION,
                                "Unknown provider: ${command.providerId}",
                                "$path.providerId",
                            )
                    else
                        state =
                            state.copy(
                                providers =
                                    state.providers.map {
                                        if (
                                            it.id == command.providerId &&
                                                it.enabled != command.enabled
                                        )
                                            it.copy(enabled = command.enabled, epoch = it.epoch + 1)
                                        else it
                                    }
                            )
                }
            }
        }
        return CommandResult(state, issues)
    }

    private fun validateCandidate(state: RuntimeState): List<RuntimeProblem> {
        val issues = mutableListOf<RuntimeProblem>()
        try {
            RuntimeStateValidator.validate(state)
        } catch (e: IllegalArgumentException) {
            issues +=
                problem(ProblemCode.INVALID_CONFIGURATION, e.message ?: "Invalid configuration")
            return issues
        }
        val definitions = state.definitions.map { it.id }.toSet()
        state.presets.forEach { binding ->
            binding.preset.blocks.forEachIndexed { index, block ->
                val id = (block.source as? PromptSource.Reference)?.id
                if (id != null && id !in definitions)
                    issues +=
                        problem(
                            ProblemCode.MISSING_REFERENCE,
                            "Optional prompt reference is not registered",
                            "presets.${binding.assistantId}.blocks[$index].source",
                        )
            }
            binding.preset.historyPolicy.summary?.let { id ->
                if (id !in definitions)
                    issues +=
                        problem(
                            ProblemCode.MISSING_REFERENCE,
                            "Optional summary reference is not registered",
                            "presets.${binding.assistantId}.historyPolicy.summary",
                        )
            }
        }
        val rules = state.rules.associateBy { it.id }
        state.rules.forEach { rule ->
            rule.dependsOn
                .filter { it !in rules }
                .forEach { missing ->
                    issues +=
                        problem(
                            ProblemCode.MISSING_DEPENDENCY,
                            "Rule prerequisite is not registered: $missing",
                            "rules.${rule.id}.dependsOn",
                        )
                }
        }
        return issues
    }

    private fun problem(code: ProblemCode, message: String, path: String? = null) =
        RuntimeProblem(
            code,
            if (code == ProblemCode.MISSING_REFERENCE || code == ProblemCode.MISSING_DEPENDENCY)
                ProblemSeverity.WARNING
            else ProblemSeverity.ERROR,
            message,
            path,
        )

    private fun operation(code: ProblemCode, message: String, path: String? = null) =
        RuntimeOperationException(problem(code, message, path))

    suspend fun register(definition: ReferenceDefinition) {
        applyConfiguration(listOf(ConfigurationCommand.RegisterReference(definition)))
    }

    suspend fun setEnabled(providerId: String, enabled: Boolean) {
        applyConfiguration(listOf(ConfigurationCommand.SetEnabled(providerId, enabled)))
    }

    suspend fun begin(
        reference: ReferenceId,
        scope: ContextScope,
        sources: List<SourceVersion>,
    ): PublicationTicket? = beginAll(listOf(reference), scope, sources).singleOrNull()

    /** Reserve before asynchronous preparation can reorder source events. */
    suspend fun nextEventOrder(): Long = mutex.withLock {
        val state = current()
        commit(state.copy(sequence = state.sequence + 1))
        state.sequence + 1
    }

    /** Reserves one event's publications atomically, before any jobs are launched. */
    suspend fun beginAll(
        references: List<ReferenceId>,
        scope: ContextScope,
        sources: List<SourceVersion>,
        eventOrder: Long = 0,
        expectedConfigurationRevision: Long? = null,
    ): List<PublicationTicket> = mutex.withLock {
        requireUniqueSources(sources)
        val state = current()
        if (
            expectedConfigurationRevision != null &&
                expectedConfigurationRevision != state.configurationRevision
        ) {
            throw operation(
                ProblemCode.CONFIGURATION_CONFLICT,
                "Configuration changed before publication reservation",
            )
        }
        require(references.distinct().size == references.size) {
            "An event cannot have competing writers to one reference"
        }
        references.forEach { reference ->
            require(state.definitions.any { it.id == reference && it.staticText == null }) {
                "Reference must be a registered dynamic output: $reference"
            }
        }
        require(eventOrder in 0..state.sequence) { "Event order must be reserved by this store" }
        val order = if (eventOrder == 0L) state.sequence + 1 else eventOrder
        var sequence = maxOf(state.sequence, order)
        val tickets = references.mapNotNull { reference ->
            val provider = state.providers.single { it.id == reference.provider }
            if (!provider.enabled) return@mapNotNull null
            if (
                state.tickets.any {
                    it.scope == scope && it.reference == reference && it.eventOrder > order
                }
            ) {
                return@mapNotNull null
            }
            PublicationTicket(
                UUID.randomUUID().toString(),
                reference,
                scope,
                ++sequence,
                provider.epoch,
                sources.map { it.copy() },
                order,
            )
        }
        if (tickets.isNotEmpty())
            commit(
                state.copy(
                    sequence = sequence,
                    tickets =
                        state.tickets.filterNot { old ->
                            tickets.any { it.reference == old.reference && it.scope == old.scope }
                        } + tickets,
                )
            )
        tickets
    }

    suspend fun isCurrent(ticket: PublicationTicket): Boolean = mutex.withLock {
        val state = current()
        val provider = state.providers.singleOrNull { it.id == ticket.reference.provider }
        provider?.enabled == true &&
            provider.epoch == ticket.providerEpoch &&
            ticket in state.tickets
    }

    suspend fun areUsable(artifacts: List<Artifact>): Boolean = mutex.withLock {
        val catalog = ArtifactCatalog(current())
        artifacts.all { catalog.isUsable(it, requireCurrent = true) }
    }

    suspend fun publish(
        ticket: PublicationTicket,
        text: String,
        dependencies: List<Artifact> = emptyList(),
    ): Artifact? = mutex.withLock {
        val state = current()
        val catalog = ArtifactCatalog(state)
        val provider = state.providers.singleOrNull { it.id == ticket.reference.provider }
        if (
            provider?.enabled != true ||
                provider.epoch != ticket.providerEpoch ||
                ticket !in state.tickets ||
                state.artifacts.any { it.ticket.id == ticket.id } ||
                dependencies.any {
                    it.ticket.scope != ticket.scope ||
                        !it.isApplicableTo(ticket.sources) ||
                        !catalog.isUsable(it, requireCurrent = true)
                }
        )
            return@withLock null
        val artifact =
            Artifact(
                ticket,
                text,
                System.currentTimeMillis(),
                dependencies.map { it.ticket.id }.distinct(),
            )
        commit(state.copy(artifacts = state.artifacts + artifact))
        artifact
    }

    /** Explicit retention; preserves all ancestors of retained outputs and publication fences. */
    suspend fun pruneArtifacts(keepLatestPerReference: Int): Int = mutex.withLock {
        require(keepLatestPerReference > 0) { "Keep at least one version per scoped reference" }
        val state = current()
        val retained = ArtifactCatalog(state).retainedIds(keepLatestPerReference)
        val artifacts = state.artifacts.filter { it.ticket.id in retained }
        val removed = state.artifacts.size - artifacts.size
        if (removed > 0) commit(state.copy(artifacts = artifacts))
        removed
    }

    suspend fun definitions(): List<ReferenceDefinition> = mutex.withLock {
        current().definitions.map(::detachedDefinition)
    }

    suspend fun importPackage(pack: PromptPackage) {
        applyConfiguration(listOf(ConfigurationCommand.ImportPackage(pack)))
    }

    suspend fun removePackage(id: String) {
        applyConfiguration(listOf(ConfigurationCommand.RemovePackage(id)))
    }

    suspend fun removeReference(id: ReferenceId) {
        applyConfiguration(listOf(ConfigurationCommand.RemoveReference(id)))
    }

    suspend fun exportPackage(id: String): PromptPackage = mutex.withLock {
        detachedPackage(current().packages.single { it.id == id })
    }

    suspend fun artifacts(scope: ContextScope): List<Artifact> = mutex.withLock {
        current().artifacts.filter { it.ticket.scope == scope }.map(::detachedArtifact)
    }

    suspend fun artifactInputs(event: RuntimeEvent): List<Artifact> = mutex.withLock {
        val catalog = ArtifactCatalog(current())
        event.inputArtifacts.distinct().map { id ->
            val artifact = requireNotNull(catalog.byId[id]) { "Missing input artifact: $id" }
            require(
                artifact.ticket.scope == event.scope && artifact.isApplicableTo(event.sources)
            ) {
                "Artifact does not belong to this event context: $id"
            }
            detachedArtifact(artifact)
        }
    }

    suspend fun resolve(
        references: List<ReferenceId>,
        scope: ContextScope,
        sources: List<SourceVersion>,
    ): List<ResolvedReference> = mutex.withLock {
        requireUniqueSources(sources)
        resolve(current(), references, scope, sources)
    }

    private fun resolve(
        state: RuntimeState,
        references: List<ReferenceId>,
        scope: ContextScope,
        sources: List<SourceVersion>,
    ): List<ResolvedReference> {
        val catalog = ArtifactCatalog(state)
        return references.distinct().map { id ->
            val provider = state.providers.singleOrNull { it.id == id.provider }
            val definition = state.definitions.singleOrNull { it.id == id }
            when {
                definition == null -> ResolvedReference(id.copy(), ReferenceStatus.UNREGISTERED)
                provider?.enabled != true -> ResolvedReference(id.copy(), ReferenceStatus.DISABLED)
                definition.staticText != null ->
                    ResolvedReference(
                        id.copy(),
                        ReferenceStatus.READY,
                        text = definition.staticText,
                    )
                else -> {
                    val artifact =
                        state.artifacts.lastOrNull {
                            it.ticket.reference == id &&
                                it.ticket.scope == scope &&
                                it.ticket.providerEpoch == provider.epoch
                        }
                    when {
                        artifact == null -> ResolvedReference(id.copy(), ReferenceStatus.EMPTY)
                        !artifact.isApplicableTo(sources) ||
                            !catalog.isUsable(artifact, requireCurrent = false) ->
                            ResolvedReference(id.copy(), ReferenceStatus.STALE)
                        else ->
                            ResolvedReference(
                                id.copy(),
                                ReferenceStatus.READY,
                                detachedArtifact(artifact),
                            )
                    }
                }
            }
        }
    }

    suspend fun setPreset(assistantId: String, preset: PromptPreset) {
        applyConfiguration(listOf(ConfigurationCommand.SetPreset(assistantId, preset)))
    }

    /** Selects the preset and all reference versions under the same lock. */
    suspend fun compile(
        scope: ContextScope,
        sources: List<SourceVersion>,
        builtins: Map<String, String>,
    ): PromptSnapshot = mutex.withLock {
        requireUniqueSources(sources)
        val state = current()
        val preset =
            state.presets.singleOrNull { it.assistantId == scope.assistantId }?.preset
                ?: PromptPreset.default()
        val ids =
            preset.blocks
                .filter { it.enabled }
                .mapNotNull { (it.source as? PromptSource.Reference)?.id } +
                listOfNotNull(preset.historyPolicy.summary)
        PromptCompiler.compile(
            detachedPreset(preset),
            builtins.toMap(),
            resolve(state, ids, scope, sources),
            sources.map { it.copy() },
        )
    }

    /** Compiles one coherent prompt view and describes unavailable inputs without side effects. */
    suspend fun previewPrompt(
        scope: ContextScope,
        sources: List<SourceVersion>,
        builtins: Map<String, String>,
    ): PromptPreview = mutex.withLock {
        requireUniqueSources(sources)
        val state = current()
        val preset =
            state.presets.singleOrNull { it.assistantId == scope.assistantId }?.preset
                ?: PromptPreset.default()
        val ids =
            preset.blocks
                .filter { it.enabled }
                .mapNotNull { (it.source as? PromptSource.Reference)?.id } +
                listOfNotNull(preset.historyPolicy.summary)
        val resolved = resolve(state, ids, scope, sources)
        val issues = mutableListOf<RuntimeProblem>()
        resolved
            .filter { it.status != ReferenceStatus.READY }
            .forEach { reference ->
                issues +=
                    RuntimeProblem(
                        ProblemCode.REFERENCE_UNAVAILABLE,
                        ProblemSeverity.WARNING,
                        "Prompt reference is ${reference.status.name.lowercase()}: ${reference.id}",
                        "references.${reference.id.provider}.${reference.id.name}",
                    )
            }
        val unknownBuiltins =
            preset.blocks
                .asSequence()
                .filter { it.enabled }
                .mapNotNull { (it.source as? PromptSource.Builtin)?.key }
                .filterNot { it in builtins }
                .distinct()
                .toList()
        unknownBuiltins.forEach { key ->
            issues +=
                RuntimeProblem(
                    ProblemCode.UNKNOWN_BUILTIN,
                    ProblemSeverity.ERROR,
                    "Unknown builtin: $key",
                    "presets.${scope.assistantId}.blocks",
                )
        }
        val snapshot =
            if (unknownBuiltins.isEmpty()) {
                PromptCompiler.compile(
                    detachedPreset(preset),
                    builtins.toMap(),
                    resolved,
                    sources.map { it.copy() },
                )
            } else null
        PromptPreview(snapshot, issues)
    }

    suspend fun putRule(rule: EventRule) {
        applyConfiguration(listOf(ConfigurationCommand.PutRule(rule)))
    }

    suspend fun removeRule(id: String) {
        applyConfiguration(listOf(ConfigurationCommand.RemoveRule(id)))
    }

    suspend fun rules(): List<EventRule> = mutex.withLock { current().rules.map(::detachedRule) }

    internal suspend fun eventSnapshot(): RuntimeState = mutex.withLock { detachedState(current()) }

    fun observeConfiguration(): Flow<ConfigurationSnapshot> = flow {
        var lastRevision = Long.MIN_VALUE
        suspend fun emitLatest() {
            val value = configuration()
            if (value.configurationRevision != lastRevision) {
                lastRevision = value.configurationRevision
                emit(value)
            }
        }
        emitLatest()
        stateRevisions.collect { if (it != null) emitLatest() }
    }
        .distinctUntilChanged()

    fun observeArtifacts(scope: ContextScope): Flow<List<Artifact>> = flow {
        var last: List<Artifact>? = null
        suspend fun emitLatest() {
            val value = artifacts(scope)
            if (value != last) {
                last = value
                emit(value)
            }
        }
        emitLatest()
        stateRevisions.collect { if (it != null) emitLatest() }
    }
        .distinctUntilChanged()
}

private fun detachedDefinition(value: ReferenceDefinition) = value.copy(id = value.id.copy())

private fun detachedPackage(value: PromptPackage) =
    value.copy(entries = value.entries.map(::detachedDefinition))

private fun detachedPreset(value: PromptPreset) =
    value.copy(
        blocks =
            value.blocks.map { block ->
                val source =
                    when (val source = block.source) {
                        is PromptSource.Reference -> source.copy(id = source.id.copy())
                        is PromptSource.Literal -> source.copy()
                        is PromptSource.Builtin -> source.copy()
                        PromptSource.History -> PromptSource.History
                    }
                block.copy(source = source)
            },
        historyPolicy =
            value.historyPolicy.copy(
                summary = value.historyPolicy.summary?.copy(),
                pinnedMessageIds = value.historyPolicy.pinnedMessageIds.toSet(),
            ),
    )

private fun detachedBinding(value: PresetBinding) =
    PresetBinding(value.assistantId, detachedPreset(value.preset))

private fun detachedCondition(value: EventCondition): EventCondition =
    when (value) {
        EventCondition.Always -> EventCondition.Always
        is EventCondition.Equals -> value.copy()
        is EventCondition.All -> value.copy(conditions = value.conditions.map(::detachedCondition))
        is EventCondition.Any -> value.copy(conditions = value.conditions.map(::detachedCondition))
        is EventCondition.Not -> value.copy(condition = detachedCondition(value.condition))
    }

private fun detachedRule(value: EventRule) =
    value.copy(
        output = value.output.copy(),
        condition = detachedCondition(value.condition),
        arguments = value.arguments.toMap(),
        dependsOn = value.dependsOn.toSet(),
    )

private fun detachedTicket(value: PublicationTicket) =
    value.copy(
        reference = value.reference.copy(),
        scope = value.scope.copy(),
        sources = value.sources.map { it.copy() },
    )

private fun detachedArtifact(value: Artifact) =
    value.copy(ticket = detachedTicket(value.ticket), dependencies = value.dependencies.toList())

private fun detachedState(value: RuntimeState) =
    value.copy(
        providers = value.providers.map { it.copy() },
        definitions = value.definitions.map(::detachedDefinition),
        tickets = value.tickets.map(::detachedTicket),
        artifacts = value.artifacts.map(::detachedArtifact),
        presets = value.presets.map(::detachedBinding),
        rules = value.rules.map(::detachedRule),
        packages = value.packages.map(::detachedPackage),
    )

private fun <T, K> replaceInPlace(values: List<T>, replacement: T, key: (T) -> K): List<T> {
    val index = values.indexOfFirst { key(it) == key(replacement) }
    return if (index < 0) values + replacement
    else values.toMutableList().also { it[index] = replacement }
}

private fun replacePackageDefinitions(
    definitions: List<ReferenceDefinition>,
    pack: PromptPackage,
): List<ReferenceDefinition> {
    val existingEntries = definitions.filter { it.id.provider == pack.id }
    if (existingEntries.map { it.id } == pack.entries.map { it.id }) {
        val byId = pack.entries.associateBy { it.id }
        return definitions.map { if (it.id.provider == pack.id) byId.getValue(it.id) else it }
    }
    val result = mutableListOf<ReferenceDefinition>()
    var inserted = false
    definitions.forEach { definition ->
        if (definition.id.provider == pack.id) {
            if (!inserted) {
                result += pack.entries
                inserted = true
            }
        } else result += definition
    }
    if (!inserted) result += pack.entries
    return result
}
