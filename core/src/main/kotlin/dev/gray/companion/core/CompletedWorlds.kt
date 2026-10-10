package dev.gray.companion.core

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.coroutines.CancellationException

/** Explicit registration capability. Unmarked handlers always retain their accepted result. */
@Serializable enum class PureReplayKind { TEXT_RENDER, TEXT_EXTRACT, CARD_DEFINE, PLUGIN_TEXT, REGEX_COPY }

@Serializable data class CapturedSessionChoices(
    val characterId: String,
    val mainCallId: String,
    val personaId: String? = null,
    val packageIds: List<String> = emptyList(),
    val groupIds: List<String> = emptyList(),
    val enabledExtensionIds: List<String> = emptyList()
)

/** Captured sessions have no branch records, pending recovery or executableWorld backlinks. */
@Serializable data class PureReplayEnvironment(val id: String, val session: Session)

@Serializable data class PureReplayTarget(val key: String, val origin: ResultOrigin, val fingerprint: String)

/** canonicalInput is captured after input processing, not re-rendered from today's template. */
@Serializable data class PureReplayRecipe(
    val kind: PureReplayKind,
    val resourceId: String,
    val workflowId: String,
    val nodeId: String,
    val environmentId: String,
    val canonicalInput: String,
    val arguments: Map<String, String> = emptyMap(),
    val targets: List<PureReplayTarget> = emptyList(),
    val pluginContext: PluginExecutionContext? = null
)

/** One immutable completed world. Resources contain no sessions/saved chats/branch recursion. */
@Serializable data class CompletedWorld(
    val library: Library,
    val choices: CapturedSessionChoices,
    val input: String,
    val executionVersion: Int,
    val environments: List<PureReplayEnvironment> = emptyList(),
    val recipes: List<PureReplayRecipe> = emptyList(),
    val formatVersion: Int = 1
)

object CompletedWorlds {
    const val MAX_RECIPES = 128
    const val MAX_ENVIRONMENTS = 128
    const val MAX_RECIPE_CHARS = 256 * 1024
    const val MAX_CAPTURE_CHARS = 2 * 1024 * 1024

    fun capture(library: Library, session: Session, input: String,
                executionVersion: Int = ExecutionSemantics.CURRENT): CompletedWorld {
        val resources = CapturedResourceClosure.capture(library, session, executionVersion = executionVersion)
        return CompletedWorld(resources, CapturedSessionChoices(session.characterId, session.mainCallId,
            session.personaId, session.packageIds, session.groupIds, session.enabledExtensionIds), input, executionVersion)
    }

    /** Root preflight, credentials and the engine must select the same old definitions. */
    fun executionLibrary(live: Library, session: Session): Library {
        val world = session.executableWorld ?: return live
        requireValidWorld(world)
        return world.library.copy(schemaVersion = LibraryFormat.CURRENT_VERSION,
            sessions = listOf(session), selectedSessionId = session.id)
    }

    /** Policy overlay after pure replay; neither the stored world nor its accepted data is edited. */
    fun withExtensionPolicyForNewTurn(live: Library, session: Session): Session {
        val world = session.executableWorld ?: return session
        requireValidWorld(world)
        val policyView = world.library.copy(extensions = world.library.extensions.map { captured ->
            if (captured.memory != null) captured else {
                val current = live.extensions.firstOrNull { it.id == captured.id }
                captured.copy(enabled = current?.let { it.enabled ?: (it.id in session.enabledExtensionIds) } ?: false)
            }
        })
        return session.copy(executableWorld = world.copy(library = policyView, environments = emptyList(), recipes = emptyList()))
    }

    /** Called only after old pure replay, before a NEW turn; recovery keeps its original world. */
    fun withGlobalMemoryForNewTurn(live: Library, session: Session): Session {
        val scoped = withExtensionPolicyForNewTurn(live, session)
        val world = scoped.executableWorld ?: return scoped
        if (live.memoryConfig == null) return scoped
        val resources = GlobalMemory.overlayGlobal(live, world.library, scoped).copy(
            sessions = emptyList(), savedChats = emptyList(), chatFolders = emptyList())
        return scoped.copy(executableWorld = world.copy(library = resources,
            executionVersion = ExecutionSemantics.CURRENT, environments = emptyList(), recipes = emptyList()))
    }

    /** A checkpoint keeps complete before-values, so it needs definitions but no after-recipes. */
    fun checkpointWorld(world: CompletedWorld?): CompletedWorld? = world?.copy(environments = emptyList(), recipes = emptyList())

    internal fun compact(runtime: RuntimeSnapshot): RuntimeSnapshot {
        val world = runtime.completedWorld ?: return runtime
        val keys = world.recipes.flatMap { it.targets }.mapTo(mutableSetOf()) { it.key }
        return runtime.copy(results = runtime.results - keys,
            unavailableResultKeys = runtime.unavailableResultKeys + keys)
    }

    /** Normal completion never pins a live conversation to old definitions; only restore does. */
    internal fun restore(session: Session, runtime: RuntimeSnapshot): Session {
        val restored = session.withRuntime(runtime)
        val world = runtime.completedWorld ?: return restored.copy(executableWorld = null)
        requireValidWorld(world)
        val choices = world.choices
        return restored.copy(characterId = choices.characterId, mainCallId = choices.mainCallId,
            personaId = choices.personaId, packageIds = choices.packageIds, groupIds = choices.groupIds,
            enabledExtensionIds = choices.enabledExtensionIds, executableWorld = world)
    }

    internal fun detachedSession(session: Session, executionVersion: Int): Session = session.copy(
        messages = session.messages.map { BodyOnlyArchives.message(it, executionVersion) },
        macroHistory = session.macroHistory.mapValues { (_, chain) -> chain.map { BodyOnlyArchives.macro(it, executionVersion) } },
        turnRecords = emptyList(), pendingRun = null, executableWorld = null, cardPlacements = emptyList(), inactivePaths = emptyList()
    )

    internal fun fingerprint(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }

    internal fun requireValidWorld(world: CompletedWorld) {
        ExecutionResources.validate(world.library, world.executionVersion).firstOrNull { it.error }?.let {
            throw IllegalArgumentException(it.message)
        }
        require(world.formatVersion == 1 && ExecutionSemantics.supports(world.executionVersion)) { "已完成分支环境版本不受支持" }
        require(world.library.schemaVersion in 1..LibraryFormat.CURRENT_VERSION) { "分支资源版本不受支持" }
        require(world.recipes.isEmpty() || ExecutionSemantics(world.executionVersion).completedPureReplay) { "旧执行不能使用新重算配方" }
        require(world.library.sessions.isEmpty() && world.library.savedChats.isEmpty() && world.library.chatFolders.isEmpty()) {
            "分支资源环境不能包含会话或收藏"
        }
        require(world.recipes.size <= MAX_RECIPES && world.environments.size <= MAX_ENVIRONMENTS) { "分支重算配方超过限额" }
        require(world.environments.map { it.id }.distinct().size == world.environments.size) { "分支重算环境标识重复" }
        require(world.environments.all { it.session.turnRecords.isEmpty() && it.session.inactivePaths.isEmpty() && it.session.pendingRun == null && it.session.executableWorld == null }) {
            "重算环境不能递归包含分支或恢复记录"
        }
        val environmentSizes = world.environments.associate { environment ->
            environment.id to LibraryFormat.json.encodeToString(PureReplayEnvironment.serializer(), environment).length
        }
        var recipeChars = 0L
        world.recipes.forEach { recipe ->
            val environmentSize = environmentSizes[recipe.environmentId] ?: error("分支缺少原始重算环境")
            val size = LibraryFormat.json.encodeToString(PureReplayRecipe.serializer(), recipe).length.toLong() + environmentSize
            require(size <= MAX_RECIPE_CHARS) { "分支重算配方超过限额" }
            recipeChars += size
            require(recipeChars <= MAX_CAPTURE_CHARS) { "分支重算捕获超过限额" }
        }
        require(world.environments.all { it.id in world.recipes.map { recipe -> recipe.environmentId } }) { "分支含未引用的重算环境" }
        require(world.recipes.flatMap { it.targets }.map { it.key }.distinct().size == world.recipes.sumOf { it.targets.size }) {
            "分支重算结果目标重复"
        }
    }
}

/** Capture happens at the existing acceptance boundary; it is never a second publication path. */
internal class CompletedWorldCapture(private val world: CompletedWorld) {
    private val recipes = mutableListOf<PureReplayRecipe>()
    private val environments = linkedMapOf<String, PureReplayEnvironment>()
    private var captureChars = 0

    fun accepted(ref: PlannedNode, context: ActionContext?, outcome: ActionOutcome,
                 publication: RecoveryPublication) {
        // A later stateful writer replaces a former recipe as well as its actual value.
        val touched = publication.resultWrites.keys
        val survivors = recipes.map { it.copy(targets = it.targets.filterNot { target -> target.key in touched }) }
            .filter { it.targets.isNotEmpty() }
        recipes.clear(); recipes.addAll(survivors)
        if (context == null || !ExecutionSemantics(world.executionVersion).completedPureReplay) return
        val descriptor = ref.action?.descriptor ?: return
        val copy = ref.node.regexOutputCopy
        val kind = if (descriptor.usesModel && descriptor.execution == ActionExecution.PURE_MODEL &&
            copy.enabled && !copy.recordTrajectory) PureReplayKind.REGEX_COPY
            else descriptor.pureReplay?.takeIf { !descriptor.usesModel && !ref.node.recordTrajectory }
            ?: return
        if (kind == PureReplayKind.REGEX_COPY && !copy.enabled) return
        if (outcome.stateWrites.isNotEmpty() || outcome.activationWrites.isNotEmpty() ||
            outcome.injectionWrites.isNotEmpty() || outcome.emittedEvents.isNotEmpty() ||
            outcome.historyWrites != HistoryMemoryPatch()) return
        val allowedMacro = if (copy.enabled) setOf("result.${copy.outputKey}") else emptySet()
        if (outcome.macroWrites.keys.any { it !in allowedMacro }) return
        val keys = if (kind == PureReplayKind.REGEX_COPY) listOf(copy.outputKey)
            else listOf(ref.node.outputKey) + listOfNotNull(copy.outputKey.takeIf { copy.enabled && !copy.recordTrajectory })
        val targets = keys.distinct().mapNotNull { key ->
            publication.resultWrites[key]?.let { value ->
                publication.resultOrigins[key]?.let { PureReplayTarget(key, it, CompletedWorlds.fingerprint(value)) }
            }
        }
        if (targets.isEmpty() || recipes.size >= CompletedWorlds.MAX_RECIPES) return
        val session = context.session ?: return
        val environmentId = "${ref.workflow.id}/${ref.node.id}"
        val environment = PureReplayEnvironment(environmentId, CompletedWorlds.detachedSession(session, world.executionVersion))
        val canonical = if (kind == PureReplayKind.REGEX_COPY) outcome.text else context.input
        if (canonical.length > CompletedWorlds.MAX_RECIPE_CHARS) return
        val plugin = if (kind == PureReplayKind.PLUGIN_TEXT) try { PluginCodeActions.prepare(context) }
            catch (_: IllegalArgumentException) { return } catch (_: IllegalStateException) { return } else null
        val recipe = PureReplayRecipe(kind, targets.first().origin.resourceId, ref.workflow.id, ref.node.id,
            environmentId, canonical, context.arguments, targets, plugin)
        // A source projection that needs data outside this bounded detached environment is not
        // eligible. Preserve its actual accepted value instead of promising an unsafe recipe.
        if (kind != PureReplayKind.PLUGIN_TEXT && kind != PureReplayKind.REGEX_COPY) {
            val resources = ExecutionResources.forWorkflow(world.library, ref.workflow.id, world.executionVersion)
            val capturedContext = context.copy(session = environment.session, library = resources,
                state = environment.session.state, results = environment.session.results)
            val expected = try { OutcomeValidator(resources, environment.session).validate(ref, capturedContext,
                BranchReplay.knownOutcome(kind, capturedContext)) } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { return }
            if (targets.any { target ->
                    val value = if (target.key == ref.node.outputKey) expected.text else expected.macroWrites["result.${target.key}"]
                    value == null || CompletedWorlds.fingerprint(value) != target.fingerprint
                }) return
        }
        val size = LibraryFormat.json.encodeToString(PureReplayRecipe.serializer(), recipe).length +
            LibraryFormat.json.encodeToString(PureReplayEnvironment.serializer(), environment).length
        if (size > CompletedWorlds.MAX_RECIPE_CHARS || size > CompletedWorlds.MAX_CAPTURE_CHARS - captureChars ||
            environments.size >= CompletedWorlds.MAX_ENVIRONMENTS && environmentId !in environments) return
        environments[environmentId] = environment
        recipes += recipe
        captureChars += size
    }

    fun completed(runtime: RuntimeSnapshot): CompletedWorld {
        val finalRecipes = recipes.map { recipe -> recipe.copy(targets = recipe.targets.filter { target ->
            target.key !in runtime.unavailableResultKeys && runtime.resultOrigins[target.key] == target.origin &&
                runtime.results[target.key]?.let(CompletedWorlds::fingerprint) == target.fingerprint
        }) }.filter { it.targets.isNotEmpty() }
        val used = finalRecipes.mapTo(mutableSetOf()) { it.environmentId }
        return world.copy(environments = environments.values.filter { it.id in used }, recipes = finalRecipes)
    }
}
