package dev.gray.companion.core

/** Empty usable starting structure shared by adapters; no sample persona/world content. */
object LibraryDefaults {
    fun create(): Library {
        val card = CharacterCard(namespace = "persona", name = "新角色")
        val draftMain = MainCall(name = "默认对话")
        val main = draftMain.copy(primaryWorkflowId = draftMain.workflows.first().id, specialDefault = true,
            workflows = draftMain.workflows.map(DefaultPromptSlots::ensureMissing))
        val session = Session(characterId = card.id, mainCallId = main.id,
            title = ConversationMetadata.defaultTitle(), titlePending = false)
        val connection = ApiConnection(name = "新 API")
        val profile = ModelProfile(name = "新模型", apiConnectionId = connection.id)
        val base = Library(characters = listOf(card), mainCalls = listOf(main), sessions = listOf(session), profiles = listOf(profile),
            selectedSessionId = session.id, selectedProfileId = profile.id, connections = listOf(connection))
        // Fresh factory only. Loading old libraries/worlds never creates or migrates Memory.
        return GlobalMemory.ensureGlobal(base).library ?: base
    }
}
