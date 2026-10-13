package dev.gray.companion.core

import kotlinx.serialization.Serializable

@Serializable data class PluginMessage(val role: Role, val text: String, val speakerName: String? = null)
@Serializable data class PluginCredentialUse(
    val alias: String, val header: String = "Authorization", val prefix: String = "Bearer "
)
@Serializable data class PluginHttpRequest(
    val url: String, val method: String = "GET", val headers: Map<String, String> = emptyMap(),
    val body: String? = null, val credential: PluginCredentialUse? = null,
    val timeoutMs: Long = 30_000, val maxResponseBytes: Int = 262_144
)
/** Capturable, text-only input: no clock, IDs, gateway, credentials or mutable host bindings. */
@Serializable data class PluginExecutionContext(
    val pluginId: String, val code: PluginCode, val input: String,
    val state: Map<String, String> = emptyMap(), val results: Map<String, String> = emptyMap(),
    val messages: List<PluginMessage> = emptyList()
)
fun interface PluginCodeExecutor { suspend fun execute(context: PluginExecutionContext): String }

object PluginCodes {
    const val MAX_SOURCE_BYTES = 64 * 1024
    const val MAX_CONTEXT_BYTES = 256 * 1024
    const val MAX_OUTPUT_BYTES = 128 * 1024
    const val MAX_MAP_ENTRIES = 256
    const val MAX_MESSAGES = 32

    fun validateCode(library: Library): List<Issue> = library.extensions.flatMap { definition ->
        definition.code?.let { validate(it, "extension.${definition.id}.code") }.orEmpty()
    }
    fun validate(code: PluginCode, path: String = "plugin.code"): List<Issue> = buildList {
        if (code.runtime != "javascript" || code.version !in 1..2) add(Issue(path, "仅支持 JavaScript 插件版本 1 或 2"))
        if (code.version == 1 && code.permissions.isNotEmpty()) add(Issue("$path.permissions", "JavaScript 插件版本 1 不支持宿主权限"))
        if (code.version == 2) addAll(PluginCapabilities.validate(code.permissions, "$path.permissions"))
        if (code.source.isBlank()) add(Issue("$path.source", "插件代码不能为空"))
        if (code.source.toByteArray(Charsets.UTF_8).size > MAX_SOURCE_BYTES) add(Issue("$path.source", "插件代码超过 64 KiB"))
    }
    fun requireContext(context: PluginExecutionContext) {
        require(validate(context.code).none { it.error }) { "插件代码配置无效" }
        require(context.state.size <= MAX_MAP_ENTRIES && context.results.size <= MAX_MAP_ENTRIES && context.messages.size <= MAX_MESSAGES) {
            "插件输入项目超过限额"
        }
        require(LibraryFormat.json.encodeToString(PluginExecutionContext.serializer(), context).toByteArray(Charsets.UTF_8).size <= MAX_CONTEXT_BYTES) {
            "插件输入超过 256 KiB"
        }
    }
    fun requireOutput(text: String): String {
        require(text.toByteArray(Charsets.UTF_8).size <= MAX_OUTPUT_BYTES) { "插件输出超过 128 KiB" }
        return text
    }
}

object PluginCodeActions {
    fun prepare(context: ActionContext): PluginExecutionContext {
        val library = requireNotNull(context.library) { "插件运行缺少捕获资源" }
        val session = requireNotNull(context.session) { "插件运行缺少捕获会话" }
        val id = context.arguments["pluginId"].orEmpty()
        val definition = PluginParticipation.activeExtensions(library, session).firstOrNull { it.id == id }
            ?: error("插件未参与当前会话或不存在")
        val code = requireNotNull(definition.code) { "插件没有代码入口" }
        require(code.version < 2 || ExecutionSemantics(context.executionVersion).pluginHostCapabilities) {
            "此执行版本不支持插件宿主能力"
        }
        return PluginExecutionContext(id, code, context.input, context.state.toSortedMap(), context.results.toSortedMap(),
            context.messages.filter { !it.deleted && it.outputSlot == OutputSlot.BODY }.takeLast(PluginCodes.MAX_MESSAGES)
                .map { PluginMessage(it.role, it.text, it.speakerName) }).also(PluginCodes::requireContext)
    }
    suspend fun run(context: ActionContext, executor: PluginCodeExecutor): ActionOutcome =
        ActionOutcome(PluginCodes.requireOutput(executor.execute(prepare(context))))
}
