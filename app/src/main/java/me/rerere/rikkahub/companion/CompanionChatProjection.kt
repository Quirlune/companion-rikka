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
import dev.gray.companion.core.ConversationPaths
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
    val acceptedIds = history.mapTo(mutableSetOf()) { it.id }
    val pending = orderTurnRegions(
        pendingOutputs.filter {
            (it.text.isNotEmpty() || it.reasoningSnapshot != null) &&
                pendingOutputId(session.id, it) !in acceptedIds
        },
        turnId = { it.turnId },
        outputSlot = { it.outputSlot },
    ).map { output ->
        val id = pendingOutputId(session.id, output)
        MessageNode(
            id = id,
            messages = listOf(UIMessage(
                id = id,
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

/** One identity shared by pending message projection and its optional body presentation. */
internal fun pendingOutputId(sessionId: String, output: PendingOutput): Uuid =
    outputPresentationId(sessionId, output.turnId, output.workflowId, output.nodeId, output.outputSlot)

/** Physical output identity survives journal publication; candidate IDs remain independent. */
private fun outputPresentationId(
    sessionId: String, turnId: String?, workflowId: String, nodeId: String, slot: OutputSlot,
    occurrence: Int = 0,
): Uuid {
    val route = "$sessionId:$workflowId:$nodeId:$slot:$turnId"
    return stableUuid(if (occurrence == 0) route else "$route:occurrence:$occurrence")
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
    data class User(val messageId: String, val revisionId: String) : CompanionBranchChoice
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
    val presentationIds: Map<Uuid, Uuid>,
)

/** Display-only nodes project compatible prompt paths and their own generation candidates. */
internal fun projectCompanionHistory(session: Session): CompanionHistoryProjection {
    val visible = orderTurnRegions(
        session.messages.filterNot { it.deleted },
        turnId = { if (it.role == Role.ASSISTANT) it.turnId else null },
        outputSlot = { it.outputSlot },
    )
    val users = visible.filter { it.role == Role.USER && it.outputSlot == OutputSlot.BODY }.associateBy { it.id }
    val repliesByTurn = visible.filter { it.role == Role.ASSISTANT }.groupBy { it.turnId }
    val records = session.turnRecords.filter { it.variants.isNotEmpty() }.associateBy { it.userMessageId }
    val anchorTurns = records.mapNotNull { (turnId, _) ->
        if (users[turnId] == null) return@mapNotNull null
        val replies = repliesByTurn[turnId].orEmpty()
        val anchor = replies.firstOrNull { it.outputSlot == OutputSlot.BODY } ?: replies.firstOrNull()
            ?: return@mapNotNull null
        anchor.id to turnId
    }.toMap()

    val choices = mutableMapOf<Uuid, List<CompanionBranchChoice>>()
    val logicalIds = mutableMapOf<Uuid, String>()
    val bodyDisplays = mutableMapOf<Uuid, BodyDisplaySnapshot>()
    val presentationIds = mutableMapOf<Uuid, Uuid>()
    val regionOccurrences = mutableMapOf<String, Int>()
    val nodes = visible.map { message ->
        val anchorTurn = anchorTurns[message.id]
        val turnRecord = anchorTurn?.let(records::get)
        val turnUser = anchorTurn?.let(users::get)
        val workflowId = message.workflowId
        val sourceNodeId = message.nodeId
        val nodeId = when {
            message.role == Role.ASSISTANT && message.turnId != null &&
                workflowId != null && sourceNodeId != null -> {
                val regionKey = "turn:${message.turnId}:workflow:${message.workflowId}:node:${message.nodeId}:slot:${message.outputSlot}"
                val occurrence = regionOccurrences.getOrDefault(regionKey, 0)
                regionOccurrences[regionKey] = occurrence + 1
                outputPresentationId(session.id, message.turnId, workflowId, sourceNodeId,
                    message.outputSlot, occurrence)
            }
            anchorTurn != null -> stableUuid("turn:$anchorTurn:generation-anchor")
            else -> stableUuid(message.id)
        }
        val activeId = message.activeTextRevisionId ?: message.id
        val textCandidates = (message.textRevisions.map { revision ->
            Triple(revision.id, revision.text, revision.createdAt)
        } + Triple(activeId, message.text, message.activeTextRevisionCreatedAt ?: message.createdAt))
            .sortedWith(compareBy<Triple<String, String, Long>> { it.third }.thenBy { it.first })

        val variants = anchorTurn?.let { ConversationPaths.generationVariants(session, it) }.orEmpty()
        val publishedRecord = message.turnId?.let(records::get)
        val publishedVariant = publishedRecord?.variants?.firstOrNull { it.id == publishedRecord.selectedVariantId }
        // During regeneration the preview has fresh replies but still carries the old receipt.
        // Only receipt-owned replies may borrow its candidates; journal replies are display-only.
        val uncommittedReply = message.role == Role.ASSISTANT && publishedRecord != null &&
            (publishedVariant == null ||
                (message.id !in publishedVariant.replyIds && publishedVariant.replies.none { it.id == message.id }))
        val branchChoices = mutableListOf<CompanionBranchChoice>()
        val uiMessages = mutableListOf<UIMessage>()
        var selectedIndex = 0
        if (uncommittedReply) {
            val uiId = stableUuid("preview:${message.id}:$activeId")
            uiMessages += message.toDisplayMessage(uiId, message.activeTextRevisionCreatedAt ?: message.createdAt)
            message.bodyDisplay?.let { bodyDisplays[uiId] = it }
            logicalIds[uiId] = message.id
        } else if (message.role == Role.USER && message.outputSlot == OutputSlot.BODY) {
            ConversationPaths.userVersions(session, message.id).forEach { revision ->
                val uiId = stableUuid("user:${message.id}:${revision.id}")
                if (revision.id == activeId) selectedIndex = uiMessages.size
                uiMessages += message.copy(text = revision.text).toDisplayMessage(uiId, revision.createdAt)
                revision.bodyDisplay?.let { bodyDisplays[uiId] = it }
                branchChoices += CompanionBranchChoice.User(message.id, revision.id)
                logicalIds[uiId] = message.id
            }
        } else if (variants.isNotEmpty() && turnUser != null && turnRecord != null) {
            variants.forEach { variant ->
                // ConversationPaths resolves active and inactive replies under the same prompt/ancestry.
                val representative = variant.replies.firstOrNull { !it.deleted && it.outputSlot == OutputSlot.BODY }
                    ?: variant.replies.firstOrNull { !it.deleted } ?: return@forEach
                val representativeActiveId = representative.activeTextRevisionId ?: representative.id
                val revisions = (representative.textRevisions.map { Triple(it.id, it.text, it.createdAt) } +
                    Triple(representativeActiveId, representative.text,
                        representative.activeTextRevisionCreatedAt ?: representative.createdAt))
                    .distinctBy { it.first }
                    .sortedWith(compareBy<Triple<String, String, Long>> { it.third }.thenBy { it.first })
                revisions.forEach { (revisionId, text, createdAt) ->
                    val uiId = stableUuid("generation:${turnUser.id}:${variant.id}:$revisionId")
                    if (variant.id == turnRecord.selectedVariantId && revisionId == activeId) {
                        selectedIndex = uiMessages.size
                    }
                    uiMessages += representative.copy(text = text).toDisplayMessage(uiId, createdAt)
                    representative.displayForRevision(revisionId)?.let { bodyDisplays[uiId] = it }
                    branchChoices += CompanionBranchChoice.Generation(
                        turnUser.id, variant.id, representative.id, revisionId,
                    )
                    logicalIds[uiId] = message.id
                }
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
        uiMessages.forEach { presentationIds[it.id] = nodeId }
        MessageNode(id = nodeId, messages = uiMessages, selectIndex = selectedIndex)
    }
    return CompanionHistoryProjection(nodes, choices, logicalIds, bodyDisplays, presentationIds)
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
