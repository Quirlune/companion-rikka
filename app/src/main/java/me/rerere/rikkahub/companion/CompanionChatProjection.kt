package me.rerere.rikkahub.companion

import dev.gray.companion.PendingOutput
import dev.gray.companion.core.AvatarImage
import dev.gray.companion.core.ChatMessage
import dev.gray.companion.core.BodyDisplaySnapshot
import dev.gray.companion.core.Library
import dev.gray.companion.core.OutputSlot
import dev.gray.companion.core.Role
import dev.gray.companion.core.Session
import dev.gray.companion.core.Protocol
import dev.gray.companion.core.ReasoningSnapshot
import dev.gray.companion.core.ModelCatalog
import dev.gray.companion.core.MessageOperations
import dev.gray.companion.core.userName
import me.rerere.ai.provider.Model
import me.rerere.ai.provider.CustomBody
import me.rerere.ai.provider.CustomHeader
import me.rerere.ai.provider.ProviderSetting
import me.rerere.ai.core.MessageRole
import me.rerere.ai.ui.UIMessage
import me.rerere.ai.ui.UIMessagePart
import me.rerere.rikkahub.data.datastore.Settings
import me.rerere.rikkahub.data.model.Avatar
import me.rerere.rikkahub.data.model.Conversation
import me.rerere.rikkahub.data.model.MessageNode
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.nio.charset.StandardCharsets
import java.time.Instant
import kotlin.time.Instant as KotlinInstant
import java.util.UUID
import kotlin.uuid.Uuid

/** Presentation objects only. Companion remains the sole owner of persisted sessions and turns. */
internal data class CompanionChatProjection(val settings: Settings, val conversation: Conversation)

internal fun projectCompanionChat(
    base: Settings,
    library: Library,
    session: Session,
    pendingOutputs: List<PendingOutput>,
    history: List<MessageNode>,
    presentationSettings: Settings = projectCompanionSettings(base, library, session),
): CompanionChatProjection {
    val settings = presentationSettings
    val pending = orderTurnRegions(
        pendingOutputs.filter { it.text.isNotEmpty() || it.reasoningSnapshot != null },
        turnId = { it.turnId },
        outputSlot = { it.outputSlot },
    ).map { output ->
        val key = "${session.id}:${output.workflowId}:${output.nodeId}:${output.outputSlot}:${output.turnId}"
        MessageNode(
            id = stableUuid(key),
            messages = listOf(UIMessage(
                id = stableUuid(key),
                role = MessageRole.ASSISTANT,
                parts = buildList {
                    output.reasoningSnapshot?.takeIf { it.text.isNotBlank() || it.nativeMessage == null }
                        ?.let { add(it.toReasoningPart()) }
                    if (output.text.isNotEmpty()) add(UIMessagePart.Text(regionText(output.text, output.outputSlot)))
                },
                createdAt = kotlin.time.Instant.fromEpochMilliseconds(session.updatedAt).toLocalDateTime(TimeZone.currentSystemDefault()),
            )),
        )
    }
    return CompanionChatProjection(
        settings = settings,
        conversation = Conversation(
            id = stableUuid(session.id),
            assistantId = settings.assistantId,
            title = session.title,
            messageNodes = history + pending,
            updateAt = Instant.ofEpochMilli(session.updatedAt),
        ),
    )
}

/** Stable appearance/catalogue projection; the UI remembers it separately from streamed text. */
internal fun projectCompanionSettings(base: Settings, library: Library, session: Session): Settings {
    val card = library.characters.firstOrNull { it.id == session.characterId }
    val main = library.mainCalls.firstOrNull { it.id == session.mainCallId }
    val primary = main?.workflows?.firstOrNull { it.id == main.primaryWorkflowId }
        ?: main?.workflows?.firstOrNull()
    val quickMessageIds = base.quickMessages.map { it.id }.toSet()
    val assistants = library.characters.map { character ->
        me.rerere.rikkahub.data.model.Assistant(
            id = stableUuid(character.id),
            name = character.name,
            avatar = character.avatar.toRikkaAvatar(),
            chatModelId = if (character.id == card?.id) primary?.modelProfileId?.let(::stableUuid) else null,
            quickMessageIds = quickMessageIds,
        )
    }.ifEmpty {
        listOf(me.rerere.rikkahub.data.model.Assistant(
            id = stableUuid("__companion_empty_character__"),
            quickMessageIds = quickMessageIds,
        ))
    }
    val currentAssistantId = card?.id?.let(::stableUuid) ?: assistants.first().id
    return base.copy(
        assistants = assistants,
        assistantId = currentAssistantId,
        providers = projectCompanionProviders(library),
        chatModelId = library.selectedProfileId?.let(::stableUuid) ?: stableUuid("companion:no-selected-model"),
        displaySetting = base.displaySetting.copy(
            userNickname = library.userName(session),
        ),
    )
}

/** A model catalogue for the original picker only; credentials and execution stay in AppController. */
internal fun projectCompanionProviders(library: Library): List<ProviderSetting> {
    val connections = library.connections.associateBy { it.id }
    return library.profiles.groupBy { it.apiConnectionId ?: ModelCatalog.legacyConnectionId(it.id) }
        .map { (connectionId, profiles) ->
            val first = profiles.first()
            val connection = connections[connectionId]
            val models = profiles.map { profile ->
                Model(id = stableUuid(profile.id), modelId = profile.model, displayName = profile.name,
                    customHeaders = connection?.customHeaders.orEmpty().map { CustomHeader(it.name, it.value) },
                    customBodies = profile.customBody.map { CustomBody(it.key, it.value) })
            }
            val id = stableUuid(connectionId)
            val enabled = connection?.enabled ?: (first.apiConnectionId == null)
            val name = connection?.name?.ifBlank { connection.protocol.name } ?: first.name
            val address = connection?.baseUrl ?: first.baseUrl
            when (connection?.protocol ?: first.protocol) {
                Protocol.OPENAI -> ProviderSetting.OpenAI(id = id, enabled = enabled, name = name,
                    models = models, baseUrl = address, useResponseApi = connection?.responseApi ?: first.responseApi)
                Protocol.GOOGLE -> ProviderSetting.Google(id = id, enabled = enabled, name = name,
                    models = models, baseUrl = address)
                Protocol.CLAUDE -> ProviderSetting.Claude(id = id, enabled = enabled, name = name,
                    models = models, baseUrl = address)
            }
        }
}

internal sealed interface CompanionBranchChoice {
    data class Text(val messageId: String, val revisionId: String) : CompanionBranchChoice
    data class Opening(val messageId: String, val revisionId: String) : CompanionBranchChoice
    data class Generation(
        val turnId: String,
        val variantId: String,
        val messageId: String? = null,
        val revisionId: String? = null,
    ) : CompanionBranchChoice
}

internal data class CompanionHistoryProjection(
    val nodes: List<MessageNode>,
    val branchChoices: Map<Uuid, List<CompanionBranchChoice>>,
    val logicalMessageIds: Map<Uuid, String>,
    val bodyDisplays: Map<Uuid, BodyDisplaySnapshot>,
)

/** Display-only MessageNodes keep the original branch selector and selected-index behavior. */
internal fun projectCompanionHistory(session: Session): CompanionHistoryProjection {
    val visible = orderTurnRegions(
        session.messages.filterNot { it.deleted },
        turnId = { if (it.role == Role.ASSISTANT) it.turnId else null },
        outputSlot = { it.outputSlot },
    )
    val users = visible.filter { it.role == Role.USER && it.outputSlot == OutputSlot.BODY }.associateBy { it.id }
    val repliesByTurn = visible.filter { it.role == Role.ASSISTANT }.groupBy { it.turnId }
    val records = session.turnRecords.filter { it.variants.isNotEmpty() }.associateBy { it.userMessageId }
    val activeById = session.messages.associateBy { it.id }
    val anchorTurns = records.mapNotNull { (turnId, _) ->
        val user = users[turnId] ?: return@mapNotNull null
        val replies = repliesByTurn[turnId].orEmpty()
        val anchor = replies.firstOrNull { it.outputSlot == OutputSlot.BODY } ?: replies.firstOrNull() ?: user
        anchor.id to turnId
    }.toMap()

    val choices = mutableMapOf<Uuid, List<CompanionBranchChoice>>()
    val logicalIds = mutableMapOf<Uuid, String>()
    val bodyDisplays = mutableMapOf<Uuid, BodyDisplaySnapshot>()
    val regionOccurrences = mutableMapOf<String, Int>()
    val nodes = visible.map { message ->
        val anchorTurn = anchorTurns[message.id]
        val turnRecord = anchorTurn?.let(records::get)
        val turnUser = anchorTurn?.let(users::get)
        val nodeId = when {
            anchorTurn != null -> stableUuid("turn:$anchorTurn:generation-anchor")
            message.role == Role.ASSISTANT && message.turnId != null &&
                message.workflowId != null && message.nodeId != null -> {
                val regionKey = "turn:${message.turnId}:workflow:${message.workflowId}:node:${message.nodeId}:slot:${message.outputSlot}"
                val occurrence = regionOccurrences.getOrDefault(regionKey, 0)
                regionOccurrences[regionKey] = occurrence + 1
                stableUuid("$regionKey:occurrence:$occurrence")
            }
            else -> stableUuid(message.id)
        }
        val activeId = message.activeTextRevisionId ?: message.id
        val textCandidates = (message.textRevisions.map { revision ->
            Triple(revision.id, revision.text, revision.createdAt)
        } + Triple(activeId, message.text, message.activeTextRevisionCreatedAt ?: message.createdAt))
            .sortedWith(compareBy<Triple<String, String, Long>> { it.third }.thenBy { it.first })

        val variants = turnRecord?.variants.orEmpty()
        val branchChoices = mutableListOf<CompanionBranchChoice>()
        val uiMessages = mutableListOf<UIMessage>()
        var selectedIndex = 0
        if (variants.isNotEmpty() && turnUser != null && turnRecord != null) {
            variants.forEach { variant ->
                val replies = MessageOperations.repliesForVariant(session, turnRecord, variant, activeById)
                val representative = replies.firstOrNull { !it.deleted && it.outputSlot == OutputSlot.BODY }
                    ?: replies.firstOrNull { !it.deleted } ?: turnUser
                val frozen = representative.textRevisions.firstOrNull { it.bodyDisplay != null }
                val original = representative.textRevisions.firstOrNull { it.id == representative.id }
                val generationId = when {
                    representative.bodyDisplay != null -> representative.activeTextRevisionId ?: representative.id
                    frozen != null -> frozen.id
                    else -> original?.id ?: representative.activeTextRevisionId ?: representative.id
                }
                val generationText = when {
                    representative.bodyDisplay != null -> representative.text
                    frozen != null -> frozen.text
                    else -> original?.text ?: representative.text
                }
                val generationTime = when {
                    representative.bodyDisplay != null -> representative.activeTextRevisionCreatedAt ?: representative.createdAt
                    frozen != null -> frozen.createdAt
                    else -> original?.createdAt ?: representative.activeTextRevisionCreatedAt ?: representative.createdAt
                }
                val generationDisplay = representative.bodyDisplay ?: frozen?.bodyDisplay ?: original?.bodyDisplay
                val uiId = stableUuid("generation:${turnUser.id}:${variant.id}")
                if (variant.id == turnRecord.selectedVariantId &&
                    (representative.activeTextRevisionId ?: representative.id) == generationId) {
                    selectedIndex = uiMessages.size
                }
                uiMessages += representative.copy(text = generationText).toDisplayMessage(uiId, generationTime)
                generationDisplay?.let { bodyDisplays[uiId] = it }
                branchChoices += CompanionBranchChoice.Generation(
                    turnUser.id, variant.id,
                    representative.id, generationId,
                )
                logicalIds[uiId] = message.id
            }
            val selectedGenerationId = branchChoices.filterIsInstance<CompanionBranchChoice.Generation>()
                .firstOrNull { it.variantId == turnRecord.selectedVariantId }?.revisionId
            val editedCandidates = (message.textRevisions.map { Triple(it.id, it.text, it.createdAt) } +
                Triple(activeId, message.text, message.activeTextRevisionCreatedAt ?: message.createdAt))
                .filterNot { it.first == selectedGenerationId }
                .sortedWith(compareBy<Triple<String, String, Long>> { it.third }.thenBy { it.first })
            editedCandidates.forEach { (revisionId, text, createdAt) ->
                val uiId = stableUuid("text:${message.id}:$revisionId")
                if (revisionId == activeId) selectedIndex = uiMessages.size
                uiMessages += message.copy(text = text).toDisplayMessage(uiId, createdAt)
                message.displayForRevision(revisionId)?.let { bodyDisplays[uiId] = it }
                branchChoices += if (message.authoredOpening) CompanionBranchChoice.Opening(message.id, revisionId)
                    else CompanionBranchChoice.Text(message.id, revisionId)
                logicalIds[uiId] = message.id
            }
        } else {
            textCandidates.forEach { (revisionId, text, createdAt) ->
                val uiId = stableUuid("text:${message.id}:$revisionId")
                if (revisionId == activeId) selectedIndex = uiMessages.size
                uiMessages += message.copy(text = text).toDisplayMessage(uiId, createdAt)
                message.displayForRevision(revisionId)?.let { bodyDisplays[uiId] = it }
                branchChoices += if (message.authoredOpening) CompanionBranchChoice.Opening(message.id, revisionId)
                    else CompanionBranchChoice.Text(message.id, revisionId)
                logicalIds[uiId] = message.id
            }
        }
        choices[nodeId] = branchChoices
        MessageNode(id = nodeId, messages = uiMessages, selectIndex = selectedIndex)
    }
    return CompanionHistoryProjection(nodes, choices, logicalIds, bodyDisplays)
}

private fun ChatMessage.displayForRevision(revisionId: String): BodyDisplaySnapshot? =
    if ((activeTextRevisionId ?: id) == revisionId) bodyDisplay
    else textRevisions.firstOrNull { it.id == revisionId }?.bodyDisplay

private fun ChatMessage.toDisplayMessage(id: Uuid, timestamp: Long = createdAt): UIMessage = UIMessage(
    id = id,
    role = when (role) {
        Role.USER -> MessageRole.USER
        Role.ASSISTANT -> MessageRole.ASSISTANT
        Role.SYSTEM -> MessageRole.SYSTEM
    },
    parts = buildList {
        if (role == Role.ASSISTANT) reasoningSnapshot?.takeIf { it.text.isNotBlank() }?.let { add(it.toReasoningPart()) }
        add(UIMessagePart.Text(if (role == Role.ASSISTANT) regionText(text, outputSlot) else text))
    },
    createdAt = kotlin.time.Instant.fromEpochMilliseconds(timestamp).toLocalDateTime(TimeZone.currentSystemDefault()),
)

private fun ReasoningSnapshot.toReasoningPart() = UIMessagePart.Reasoning(
    reasoning = text,
    createdAt = KotlinInstant.fromEpochMilliseconds(startedAt),
    finishedAt = finishedAt?.let { KotlinInstant.fromEpochMilliseconds(it) },
)

/** Only adjacent outputs from a known turn move; legacy messages and user boundaries stay intact. */
private fun <T> orderTurnRegions(
    outputs: List<T>,
    turnId: (T) -> String?,
    outputSlot: (T) -> OutputSlot,
): List<T> = buildList(outputs.size) {
    var index = 0
    while (index < outputs.size) {
        val start = index++
        val turn = turnId(outputs[start])
        if (turn == null) {
            add(outputs[start])
        } else {
            while (index < outputs.size && turnId(outputs[index]) == turn) index++
            addAll(outputs.subList(start, index).sortedBy { outputSlot(it).ordinal })
        }
    }
}

/** Region labels are presentation-only and never enter persisted messages or model history. */
private fun regionText(text: String, slot: OutputSlot): String = when {
    text.isEmpty() || slot == OutputSlot.BODY -> text
    slot == OutputSlot.BEFORE_BODY -> "**正文之前**\n\n$text"
    else -> "**正文之后**\n\n$text"
}

private fun AvatarImage?.toRikkaAvatar(): Avatar = this?.let {
    Avatar.Image("data:${it.mediaType};base64,${it.base64}")
} ?: Avatar.Dummy

internal fun stableUuid(value: String): Uuid = try {
    Uuid.parse(value)
} catch (_: IllegalArgumentException) {
    Uuid.parse(UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8)).toString())
}
