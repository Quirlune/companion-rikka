package me.rerere.prompt

/** Index over one committed state, shared by provenance checks and retention. */
internal class ArtifactCatalog(private val state: RuntimeState) {
    val byId = state.artifacts.associateBy { it.ticket.id }
    private val providers = state.providers.associateBy { it.id }
    private val latest = state.artifacts.associateBy { it.ticket.scope to it.ticket.reference }
    private val currentTickets = state.tickets.map { it.id }.toSet()

    fun isUsable(artifact: Artifact, requireCurrent: Boolean): Boolean {
        if (byId[artifact.ticket.id] != artifact) return false
        val pending = ArrayDeque<Artifact>()
        val visited = mutableSetOf<String>()
        pending.add(artifact)
        while (pending.isNotEmpty()) {
            val item = pending.removeFirst()
            if (!visited.add(item.ticket.id)) continue
            val ticket = item.ticket
            val provider = providers[ticket.reference.provider] ?: return false
            if (!provider.enabled || provider.epoch != ticket.providerEpoch) return false
            if (latest[ticket.scope to ticket.reference]?.ticket?.id != ticket.id) return false
            if (requireCurrent && ticket.id !in currentTickets) return false
            for (id in item.dependencies) pending.add(byId[id] ?: return false)
        }
        return true
    }

    fun retainedIds(keepLatest: Int): Set<String> {
        val roots =
            state.artifacts
                .groupBy { it.ticket.scope to it.ticket.reference }
                .values
                .flatMap { it.takeLast(keepLatest) }
        val pending = ArrayDeque(roots.map { it.ticket.id })
        val retained = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (retained.add(id)) pending.addAll(byId[id]?.dependencies.orEmpty())
        }
        return retained
    }
}
