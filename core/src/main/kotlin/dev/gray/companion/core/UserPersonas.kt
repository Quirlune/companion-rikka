package dev.gray.companion.core

/** Only a session's saved selection changes its active user identity. */
fun Library.effectivePersona(session: Session): UserPersona? =
    session.personaId?.let { id -> personas.firstOrNull { it.id == id } }

fun Library.userName(session: Session): String = effectivePersona(session)?.name ?: user.name

fun Library.userPrompt(session: Session): String = effectivePersona(session)?.prompt.orEmpty()

/** A session switch freezes legacy user floor labels before changing the active persona. */
fun Library.selectPersonaForSession(sessionId: String, personaId: String?): Library {
    require(personaId == null || personas.any { it.id == personaId }) { "用户身份不存在" }
    val current = requireNotNull(sessions.firstOrNull { it.id == sessionId }) { "会话不存在" }
    if (current.personaId == personaId) return this
    val formerName = userName(current)
    val updated = current.copy(personaId = personaId, messages = current.messages.map { message ->
        if (message.role == Role.USER && message.speakerName == null) message.copy(speakerName = formerName) else message
    })
    return copy(sessions = sessions.map { if (it.id == sessionId) updated else it })
}
