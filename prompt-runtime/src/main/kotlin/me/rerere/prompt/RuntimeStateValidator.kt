package me.rerere.prompt

/**
 * Rejects inconsistent persisted graphs before they enter the runtime; never repairs by deletion.
 */
internal object RuntimeStateValidator {
    fun validate(state: RuntimeState) {
        require(state.schemaVersion == 1) { "Unsupported runtime schema: ${state.schemaVersion}" }
        require(state.sequence >= 0) { "Negative runtime sequence" }
        require(state.configurationRevision >= 0) { "Negative configuration revision" }
        require(state.stateRevision >= 0) { "Negative state revision" }
        require(state.configurationRevision <= state.stateRevision) { "Configuration revision exceeds state revision" }
        unique(state.providers.map { it.id }, "provider ID")
        unique(state.definitions.map { it.id }, "reference ID")
        unique(state.packages.map { it.id }, "package ID")
        unique(state.presets.map { it.assistantId }, "preset binding")
        unique(state.rules.map { it.id }, "rule ID")
        unique(state.tickets.map { it.id }, "current ticket ID")
        unique(state.tickets.map { it.scope to it.reference }, "current output ticket")
        unique(state.artifacts.map { it.ticket.id }, "artifact ID")
        require(state.presets.all { it.assistantId.isNotBlank() }) { "Empty assistant binding" }
        val providers = state.providers.associateBy { it.id }
        val definitions = state.definitions.associateBy { it.id }
        val packages = state.packages.associateBy { it.id }
        state.providers.forEach {
            require(it.id.isNotBlank() && it.epoch >= 0) { "Invalid provider: ${it.id}" }
        }
        state.definitions.forEach {
            require(it.id.provider in providers) { "Missing provider for ${it.id}" }
            require((it.staticText != null) == (it.id.provider in packages)) {
                "Reference namespace type mismatch: ${it.id}"
            }
        }
        state.packages.forEach { pack ->
            require(pack.id in providers) { "Missing package provider: ${pack.id}" }
            require(
                pack.entries.toSet() ==
                    state.definitions.filter { it.id.provider == pack.id }.toSet()
            ) {
                "Package entries differ from registered definitions: ${pack.id}"
            }
        }
        state.rules.forEach {
            require(
                definitions[it.output]?.let { definition -> definition.staticText == null } == true
            ) {
                "Rule has no dynamic output: ${it.id}"
            }
        }
        val allTickets = state.tickets + state.artifacts.map { it.ticket }
        val ticketsById = allTickets.groupBy { it.id }
        require(ticketsById.values.all { it.distinct().size == 1 }) {
            "Conflicting copies of a ticket"
        }
        unique(ticketsById.values.map { it.first().sequence }, "ticket sequence")
        allTickets.forEach { ticket ->
            val definition = definitions[ticket.reference]
            require(definition != null && definition.staticText == null) {
                "Missing ticket output: ${ticket.id}"
            }
            require(
                ticket.sequence in 1..state.sequence && ticket.eventOrder in 0..ticket.sequence
            ) {
                "Invalid ticket order: ${ticket.id}"
            }
            require(
                ticket.providerEpoch in 0..providers.getValue(ticket.reference.provider).epoch
            ) {
                "Invalid ticket epoch: ${ticket.id}"
            }
            require(ticket.id.isNotBlank()) { "Empty ticket ID" }
            requireUniqueSources(ticket.sources)
        }
        val fences = state.tickets.associateBy { it.scope to it.reference }
        state.artifacts
            .groupBy { it.ticket.scope to it.ticket.reference }
            .forEach { (key, artifacts) ->
                require(
                    artifacts.zipWithNext().all { (a, b) -> a.ticket.sequence < b.ticket.sequence }
                ) {
                    "Artifact versions are not ordered: $key"
                }
                val latest = artifacts.last().ticket
                require((fences[key]?.sequence ?: -1) >= latest.sequence) {
                    "Missing publication fence: $key"
                }
            }
        validateProvenance(state.artifacts)
    }

    private fun validateProvenance(artifacts: List<Artifact>) {
        val byId = artifacts.associateBy { it.ticket.id }
        val remaining = artifacts.associate { it.ticket.id to it.dependencies.size }.toMutableMap()
        val children = mutableMapOf<String, MutableList<String>>()
        artifacts.forEach { artifact ->
            unique(artifact.dependencies, "artifact dependency")
            artifact.dependencies.forEach { id ->
                val input = requireNotNull(byId[id]) { "Missing artifact dependency: $id" }
                require(
                    input.ticket.scope == artifact.ticket.scope &&
                        input.isApplicableTo(artifact.ticket.sources)
                ) {
                    "Invalid dependency context: ${artifact.ticket.id} -> $id"
                }
                children.getOrPut(id) { mutableListOf() }.add(artifact.ticket.id)
            }
        }
        val ready = ArrayDeque(remaining.filterValues { it == 0 }.keys)
        var visited = 0
        while (ready.isNotEmpty()) {
            val id = ready.removeFirst()
            visited++
            children[id].orEmpty().forEach { child ->
                val count = remaining.getValue(child) - 1
                remaining[child] = count
                if (count == 0) ready.add(child)
            }
        }
        require(visited == artifacts.size) { "Cyclic artifact provenance" }
    }

    private fun <T> unique(values: List<T>, label: String) {
        require(values.distinct().size == values.size) { "Duplicate $label" }
    }
}

internal fun requireUniqueSources(sources: List<SourceVersion>) {
    require(sources.map { it.id }.distinct().size == sources.size) { "Duplicate source message ID" }
}
