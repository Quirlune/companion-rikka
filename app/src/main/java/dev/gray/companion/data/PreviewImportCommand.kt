package dev.gray.companion.data

import android.content.Context
import dev.gray.companion.AppController
import dev.gray.companion.core.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** Debug-only data ingestion, using the same controller transaction as document imports. */
internal object PreviewImportCommand {
    const val ACTION = "dev.gray.companion.PREVIEW_IMPORT"
    private val json = Json { encodeDefaults = true; prettyPrint = true }

    @Serializable
    private data class Report(
        val success: Boolean,
        val issues: List<Issue>,
        val selectedSessionId: String?,
        val character: String?,
        val mainCall: String?,
        val characterCount: Int,
        val mainCallCount: Int,
        val packageCount: Int,
        val groupCount: Int,
        val personaCount: Int,
        val sessionCount: Int,
        val openingMessages: Int,
        val modelReady: Boolean,
        val promptMessages: Int,
        val promptIssues: List<Issue>,
        val statusXmlReady: Boolean,
    )

    suspend fun run(context: Context, controller: AppController) {
        controller.state.first { !it.loading }
        val issues = try {
            val source = withContext(Dispatchers.IO) {
                val sourceFile = File(context.filesDir, "preview-import.json")
                require(sourceFile.isFile) { "尚未放入测试导入文件" }
                sourceFile.readText()
            }
            val backup = controller.export()
            withContext(Dispatchers.IO) {
                File(context.filesDir, "preview-before-import-${System.currentTimeMillis()}.json").writeText(backup)
            }
            controller.importBundle(source, selectImportedSession = true)
        } catch (_: Exception) {
            listOf(Issue("preview.import", "测试导入失败；请检查输入文件，已有资源未被替换"))
        }
        val library = controller.library.value
        val session = library.sessions.firstOrNull { it.id == library.selectedSessionId }
        val main = library.mainCalls.firstOrNull { it.id == session?.mainCallId }
        val primary = main?.workflows?.firstOrNull { it.id == main.primaryWorkflowId } ?: main?.workflows?.firstOrNull()
        val node = primary?.nodes?.firstOrNull { it.enabled && it.actionId == "model.generate" }
        val prompt = if (session != null && node != null) PromptCompiler().preview(library, session, node, "") else null
        val profile = ModelCatalog.resolveProfile(library)
        val ready = profile?.takeIf { it.model.isNotBlank() }?.let { configured ->
            withContext(Dispatchers.IO) { controller.getApiKey(ModelCatalog.credentialId(configured)).isNotBlank() }
        } == true
        val statusXmlReady = StatusFields.readAll("<StatusBlock><state>check</state></StatusBlock>", listOf("state"))["state"] == "check"
        val report = Report(
            success = issues.none { it.error }, issues = issues,
            selectedSessionId = session?.id,
            character = library.characters.firstOrNull { it.id == session?.characterId }?.name,
            mainCall = main?.name,
            characterCount = library.characters.size, mainCallCount = library.mainCalls.size,
            packageCount = library.packages.size, groupCount = library.groups.size,
            personaCount = library.personas.size, sessionCount = library.sessions.size,
            openingMessages = session?.messages?.size ?: 0, modelReady = ready,
            promptMessages = prompt?.messages?.size ?: 0, promptIssues = prompt?.issues.orEmpty(),
            statusXmlReady = statusXmlReady,
        )
        withContext(Dispatchers.IO) {
            if (prompt != null) File(context.filesDir, "preview-import-prompt.json")
                .writeText(json.encodeToString(PromptPreview.serializer(), prompt))
            File(context.filesDir, "preview-import-result.json")
                .writeText(json.encodeToString(Report.serializer(), report))
        }
    }
}
