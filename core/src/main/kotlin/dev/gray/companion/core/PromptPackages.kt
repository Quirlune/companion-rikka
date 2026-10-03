package dev.gray.companion.core

/** Availability only. Activation and insertion remain the compiler's responsibility. */
fun Library.loadedPromptPackages(
    session: Session,
    mainCall: MainCall? = mainCalls.firstOrNull { it.id == session.mainCallId }
): List<PromptPackage> {
    val ids = linkedSetOf<String>()
    ids.addAll(session.packageIds)
    characters.firstOrNull { it.id == session.characterId }?.let { ids.addAll(it.boundPackageIds) }
    mainCall?.let { ids.addAll(it.boundPackageIds) }
    loadedCallGroups(session, mainCall).forEach { ids.addAll(it.boundPackageIds) }
    val packagesById = packages.associateBy { it.id }
    return ids.mapNotNull { packagesById[it] }
}

fun Library.loadedCallGroups(
    session: Session,
    mainCall: MainCall? = mainCalls.firstOrNull { it.id == session.mainCallId }
): List<CallGroup> {
    val ids = linkedSetOf<String>()
    ids.addAll(session.groupIds)
    mainCall?.let { ids.addAll(it.boundGroupIds) }
    val byId = groups.associateBy { it.id }
    return ids.mapNotNull { byId[it] }
}
