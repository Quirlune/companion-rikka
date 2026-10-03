package dev.gray.companion.core

/** Empty usable starting structure shared by adapters; no sample persona/world content. */
object LibraryDefaults {
    fun create(): Library {
        val card = CharacterCard(namespace = "persona", name = "新角色")
        val draftMain = MainCall(name = "默认对话")
        val main = draftMain.copy(primaryWorkflowId = draftMain.workflows.first().id)
        val session = Session(characterId = card.id, mainCallId = main.id)
        val connection = ApiConnection(name = "新 API")
        val profile = ModelProfile(name = "新模型", apiConnectionId = connection.id)
        return Library(characters = listOf(card), mainCalls = listOf(main), sessions = listOf(session), profiles = listOf(profile),
            selectedSessionId = session.id, selectedProfileId = profile.id, connections = listOf(connection))
    }
}
