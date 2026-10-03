package me.rerere.rikkahub.data.ai.runtime

import kotlin.uuid.Uuid
import kotlinx.coroutines.flow.last
import kotlinx.serialization.json.Json
import me.rerere.prompt.TaskAction
import me.rerere.prompt.TaskInput
import me.rerere.rikkahub.data.ai.GenerationChunk
import me.rerere.rikkahub.data.ai.GenerationLoop
import me.rerere.rikkahub.data.ai.transformers.DocumentAsPromptTransformer
import me.rerere.rikkahub.data.ai.transformers.OcrTransformer
import me.rerere.rikkahub.data.ai.transformers.PlaceholderTransformer
import me.rerere.rikkahub.data.datastore.SettingsStore
import me.rerere.rikkahub.data.datastore.findModelById
import me.rerere.rikkahub.data.datastore.getAssistantById

/** A detached model call: no chat writes, tools or reply-completed events. */
class ModelTaskAction(
    private val settingsStore: SettingsStore,
    private val generationLoop: GenerationLoop,
    private val json: Json,
) : TaskAction {
    override suspend fun execute(input: TaskInput): String {
        val settings = settingsStore.settingsFlow.value
        val assistantId = input.rule.arguments["assistantId"] ?: input.event.scope.assistantId
        val assistant =
            settings.getAssistantById(Uuid.parse(assistantId)) ?: error("Task assistant not found")
        val modelId =
            input.rule.arguments["modelId"]?.let(Uuid::parse)
                ?: assistant.chatModelId
                ?: settings.chatModelId
        val model = settings.findModelById(modelId) ?: error("Task model not found")
        val taskAssistant =
            assistant.copy(
                systemPrompt = input.rule.arguments["instructions"] ?: assistant.systemPrompt,
                enableMemory = false,
            )
        // Always append a fresh user request so providers do not continue the source assistant
        // message.
        val messages =
            PromptRuntimeService.messages(input.event, json) +
                me.rerere.ai.ui.UIMessage.user(
                    input.rule.arguments["request"] ?: "Analyze the supplied material."
                )
        val result =
            generationLoop
                .generateText(
                    settings = settings,
                    model = model,
                    messages = messages,
                    assistant = taskAssistant,
                    maxSteps = 1,
                    promptContext =
                        PromptRequestContext(
                            input.event.scope.copy(assistantId = assistant.id.toString()),
                            input.event.sources,
                        ),
                    inputTransformers =
                        listOf(PlaceholderTransformer, DocumentAsPromptTransformer, OcrTransformer),
                )
                .last() as GenerationChunk.Messages
        return result.messages.last().toText()
    }

    companion object {
        const val ID = "model.generate"
    }
}
