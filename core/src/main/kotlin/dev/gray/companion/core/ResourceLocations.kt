package dev.gray.companion.core

import kotlinx.serialization.Serializable

/** Stable editor destination. section identifies an original editor control, never a reflective setter. */
@Serializable data class ResourceLocation(
    val resource: ExtensionResourceRef,
    val entryId: String? = null,
    val workflowId: String? = null,
    val nodeId: String? = null,
    val blockId: String? = null,
    val field: ExtensionFieldRef? = null,
    val section: String? = null,
    val spanStart: Int? = null,
    val spanEndExclusive: Int? = null
)

object ResourceLocations {
    fun fromField(target: ExtensionFieldRef) = ResourceLocation(
        ExtensionResourceRef(target.kind, target.resourceId), target.entryId, target.workflowId,
        target.nodeId, target.blockId, target)

    fun path(location: ResourceLocation): String = buildString {
        append(when (location.resource.kind) {
            ExtensionResourceKind.CHARACTER -> "character"
            ExtensionResourceKind.PACKAGE -> "package"
            ExtensionResourceKind.MAIN -> "main"
            ExtensionResourceKind.GROUP -> "group"
        })
        append('.'); append(location.resource.id)
        location.entryId?.let { append(".entry."); append(it) }
        location.workflowId?.let { append(".workflow."); append(it) }
        location.nodeId?.let { append(".node."); append(it) }
        location.blockId?.let { append(".block."); append(it) }
        location.field?.let { append(".field."); append(it.field.name); it.argDescriptorKey?.let { key -> append('.'); append(key) } }
        location.section?.let { append('.'); append(it) }
    }

    fun resources(library: Library): List<ExtensionResourceRef> =
        library.characters.map { ExtensionResourceRef(ExtensionResourceKind.CHARACTER, it.id) } +
            library.mainCalls.map { ExtensionResourceRef(ExtensionResourceKind.MAIN, it.id) } +
            library.packages.map { ExtensionResourceRef(ExtensionResourceKind.PACKAGE, it.id) } +
            library.groups.map { ExtensionResourceRef(ExtensionResourceKind.GROUP, it.id) }

    fun resolveIssue(library: Library, issue: Issue): ResourceLocation? {
        val issuePath = issue.path
        // Longest exact registered target wins, retaining argument and every child ID.
        val fields = resources(library).flatMap { ExtensionFieldRegistry.list(library, it) }.map { fromField(it.target) }
        fields.sortedByDescending { path(it).length }.firstOrNull { issuePath == path(it) || issuePath.startsWith(path(it) + ".") }?.let { return it }
        // Registry failures retain IDs even when the addressed child disappeared in another editor.
        for (ref in resources(library)) {
            val prefix = path(ResourceLocation(ref)) + "."
            if (!issuePath.startsWith(prefix) || ".field." !in issuePath) continue
            val tail = issuePath.removePrefix(prefix)
            val fieldPart = tail.substringAfter("field.")
            val field = ExtensionField.entries.firstOrNull { it.name == fieldPart.substringBefore('.') } ?: continue
            fun child(name: String): String? {
                val marker = "$name."
                if (!tail.startsWith(marker) && ".$marker" !in tail) return null
                val value = if (tail.startsWith(marker)) tail.removePrefix(marker) else tail.substringAfter(".$marker")
                return value.split(Regex("\\.(?:entry|workflow|node|block|field)\\."), limit = 2).first()
            }
            return fromField(ExtensionFieldRef(ref.kind, ref.id, field, child("entry"), child("workflow"), child("node"), child("block"),
                fieldPart.substringAfter('.', "").takeIf { field == ExtensionField.NODE_ARGUMENT && it.isNotEmpty() }))
        }
        val locations = buildList {
            for (ref in resources(library)) {
                add(ResourceLocation(ref))
                ResourceEditCommands.entries(library, ref).forEach { add(ResourceLocation(ref, entryId = it.id)) }
                ResourceTemplateSlots.workflows(library, ref).forEach { workflow ->
                    add(ResourceLocation(ref, workflowId = workflow.id))
                    workflow.nodes.forEach { node ->
                        add(ResourceLocation(ref, workflowId = workflow.id, nodeId = node.id))
                        node.blocks.forEach { add(ResourceLocation(ref, workflowId = workflow.id, nodeId = node.id, blockId = it.id)) }
                    }
                }
                val tiles = when (ref.kind) {
                    ExtensionResourceKind.MAIN -> library.mainCalls.first { it.id == ref.id }.tiles
                    ExtensionResourceKind.GROUP -> library.groups.first { it.id == ref.id }.tiles
                    else -> emptyList()
                }
                tiles.forEach { if (issuePath == "tile.${it.id}" || issuePath.startsWith("tile.${it.id}.")) add(ResourceLocation(ref, section = "tiles.${it.id}")) }
            }
        }
        val matches = locations.mapNotNull { candidate ->
            val workflowPath = candidate.workflowId?.let { id -> "workflow.$id" +
                (candidate.nodeId?.let { ".node.$it" } ?: "") + (candidate.blockId?.let { ".block.$it" } ?: "") }
            val prefixes = listOfNotNull(path(candidate),
                path(candidate).takeIf { candidate.resource.kind == ExtensionResourceKind.PACKAGE }?.replaceFirst("package.", "pack."),
                candidate.entryId?.let { "entry.$it" }, workflowPath,
                candidate.section?.takeIf { it.startsWith("tiles.") }?.let { "tile.${it.removePrefix("tiles.")}" })
            prefixes.filter { issuePath == it || issuePath.startsWith("$it.") }.maxOfOrNull { it.length }?.let { candidate to it }
        }
        val longest = matches.maxOfOrNull { it.second } ?: return null
        val found = matches.filter { it.second == longest }.map { it.first }.distinct().singleOrNull() ?: return null
        val corresponding = fields.filter { it.resource == found.resource && it.entryId == found.entryId &&
            it.workflowId == found.workflowId && it.nodeId == found.nodeId && it.blockId == found.blockId }
        val suffix = issuePath.substringAfterLast('.')
        val preferred = when (suffix) {
            "text" -> ExtensionField.ENTRY_TEXT
            "template" -> ExtensionField.BLOCK_TEMPLATE
            "description" -> ExtensionField.CHARACTER_DESCRIPTION
            "role" -> ExtensionField.BLOCK_ROLE
            "enabled" -> when { found.blockId != null -> ExtensionField.BLOCK_ENABLED; found.nodeId != null -> ExtensionField.NODE_ENABLED; found.workflowId != null -> ExtensionField.WORKFLOW_ENABLED; found.entryId != null -> ExtensionField.ENTRY_ENABLED; else -> null }
            "temperature" -> ExtensionField.NODE_TEMPERATURE
            "topP" -> ExtensionField.NODE_TOP_P
            "maxTokens" -> ExtensionField.NODE_MAX_TOKENS
            "reasoning" -> ExtensionField.NODE_REASONING
            "modelProfileId" -> ExtensionField.WORKFLOW_MODEL
            "profileId" -> ExtensionField.NODE_MODEL
            "priority" -> ExtensionField.WORKFLOW_PRIORITY
            "queued" -> ExtensionField.WORKFLOW_QUEUED
            else -> if (found.blockId != null && issuePath.endsWith("block.${found.blockId}")) ExtensionField.BLOCK_TEMPLATE else null
        }
        if (".argument." in issuePath) corresponding.singleOrNull { it.field?.field == ExtensionField.NODE_ARGUMENT && it.field.argDescriptorKey == issuePath.substringAfter(".argument.") }?.let { return it }
        return corresponding.singleOrNull { preferred != null && it.field?.field == preferred } ?: found.copy(section = found.section ?: suffix)
    }

    /** All identifiable sources are returned. Multiple result producers require an explicit UI choice. */
    fun resolveReference(library: Library, owner: ExtensionFieldRef?, token: String): List<ResourceLocation> {
        val ownerLocation = owner?.let(::fromField) ?: ResourceLocation(ExtensionResourceRef(ExtensionResourceKind.MAIN, ""))
        val static = ResourceUsageIndex.staticTargets(library, ownerLocation, token)
        if (static.isNotEmpty()) return static
        val legacy = PromptReferenceLanguage.compile(token).reference as? PromptReferenceLanguage.LegacyReference ?: return emptyList()
        val address = legacy.expression.substringBefore('@').substringBefore('#')
        if (!address.startsWith("result.") && !address.startsWith("macro.")) return emptyList()
        val key = address.substringAfter('.')
        val declarations = buildList {
            library.mainCalls.forEach { main -> addAll(ResultReferences.declarations(library, Session(mainCallId = main.id, groupIds = library.groups.map { it.id }))) }
            if (library.mainCalls.isEmpty()) addAll(ResultReferences.declarations(library, Session(groupIds = library.groups.map { it.id })))
        }
        return declarations.filter { it.key == key }.mapNotNull { declaration ->
            val ref = resources(library).singleOrNull { it.id == declaration.origin.resourceId && it.kind in setOf(ExtensionResourceKind.MAIN, ExtensionResourceKind.GROUP) } ?: return@mapNotNull null
            ResourceLocation(ref, workflowId = declaration.origin.workflowId, nodeId = declaration.origin.nodeId, section = "outputKey")
        }.distinct()
    }
}

data class ResourceTemplateSlot(val location: ResourceLocation, val text: String)

/** Explicit readable inventory for advanced controls which are not public extension parameters. */
object ResourceTemplateSlots {
    fun read(library: Library, location: ResourceLocation): String? =
        list(library).singleOrNull { it.location == location.copy(spanStart = null, spanEndExclusive = null) }?.text

    internal fun workflows(library: Library, ref: ExtensionResourceRef): List<Workflow> = when (ref.kind) {
        ExtensionResourceKind.MAIN -> library.mainCalls.firstOrNull { it.id == ref.id }?.configuredWorkflows().orEmpty()
        ExtensionResourceKind.GROUP -> library.groups.firstOrNull { it.id == ref.id }?.workflows.orEmpty()
        else -> emptyList()
    }

    fun list(library: Library): List<ResourceTemplateSlot> = buildList {
        for (ref in ResourceLocations.resources(library)) {
            val root = ResourceLocation(ref)
            fun text(location: ResourceLocation, section: String, value: String) { add(ResourceTemplateSlot(location.copy(section = section), value)) }
            fun condition(location: ResourceLocation, value: Condition?) {
                value?.let { text(location, "condition.key", it.key); text(location, "condition.value", it.value); text(location, "condition.expression", it.expression) }
            }
            fun recognition(location: ResourceLocation, value: Recognition?, prefix: String = "recognition") {
                value ?: return
                value.rules.forEachIndexed { index, rule -> text(location, "$prefix.rules.$index.template", rule.template); text(location, "$prefix.rules.$index.value", rule.value) }
                value.groups.forEachIndexed { index, child -> recognition(location, child, "$prefix.groups.$index") }
            }
            fun processing(location: ResourceLocation, values: List<TextProcessStep>, prefix: String) {
                values.forEachIndexed { index, step ->
                    text(location, "$prefix.$index.text", step.text)
                    text(location, "$prefix.$index.replacement", step.replacement)
                }
            }
            ResourceEditCommands.entries(library, ref).forEach { entry ->
                val location = ResourceLocations.fromField(ExtensionFieldRef(ref.kind, ref.id, ExtensionField.ENTRY_TEXT, entryId = entry.id))
                if (entry.isTemplate) add(ResourceTemplateSlot(location, entry.text))
                condition(location.copy(field = null), entry.condition)
            }
            library.characters.firstOrNull { ref.kind == ExtensionResourceKind.CHARACTER && it.id == ref.id }?.let { card ->
                text(root, "greeting", card.greeting)
                card.alternateGreetings.forEachIndexed { index, value -> text(root, "alternateGreetings.$index", value) }
            }
            for (workflow in workflows(library, ref)) {
                val wl = root.copy(workflowId = workflow.id)
                condition(wl, workflow.condition); recognition(wl, workflow.recognition)
                for (node in workflow.nodes) {
                    val nl = wl.copy(nodeId = node.id)
                    text(nl, "inputTemplate", node.inputTemplate)
                    condition(nl, node.condition); recognition(nl, node.recognition)
                    processing(nl, node.inputProcessing, "inputProcessing"); processing(nl, node.outputProcessing, "outputProcessing")
                    text(nl, "macroProcess.initial", node.macroProcess.initial)
                    processing(nl, node.macroProcess.steps, "macroProcess.steps")
                    node.arguments.forEach { (key, value) ->
                        val target = ExtensionFieldRef(ref.kind, ref.id, ExtensionField.NODE_ARGUMENT, workflowId = workflow.id, nodeId = node.id, argDescriptorKey = key)
                        text(if (ExtensionFieldRegistry.describe(library, target) != null) ResourceLocations.fromField(target) else nl, "arguments.$key", value)
                    }
                    node.blocks.forEach { block ->
                        val target = ExtensionFieldRef(ref.kind, ref.id, ExtensionField.BLOCK_TEMPLATE, workflowId = workflow.id, nodeId = node.id, blockId = block.id)
                        val bl = nl.copy(blockId = block.id)
                        add(ResourceTemplateSlot(if (ExtensionFieldRegistry.describe(library, target) != null) ResourceLocations.fromField(target) else bl.copy(section = "template"), block.template))
                        condition(bl, block.condition); recognition(bl, block.recognition)
                        processing(bl, block.processing, "processing")
                    }
                }
            }
            val main = library.mainCalls.firstOrNull { ref.kind == ExtensionResourceKind.MAIN && it.id == ref.id }
            main?.let {
                text(root, "bodyTextTemplate", it.bodyTextTemplate)
                text(root, "history.prefix", it.history.prefix); text(root, "history.suffix", it.history.suffix)
                text(root, "history.floorPrefix", it.history.floorPrefix); text(root, "history.floorSuffix", it.history.floorSuffix)
                it.history.insertions.forEach { insertion -> text(root, "history.insertions.${insertion.id}.template", insertion.template) }
                it.history.speakers.forEach { speaker -> text(root, "history.speakers.${speaker.speaker}.prefix", speaker.prefix.orEmpty()); text(root, "history.speakers.${speaker.speaker}.suffix", speaker.suffix.orEmpty()) }
                it.history.floors.forEach { floor -> text(root, "history.floors.${floor.messageId}.prefix", floor.prefix.orEmpty()); text(root, "history.floors.${floor.messageId}.suffix", floor.suffix.orEmpty()) }
            }
            val group = library.groups.firstOrNull { ref.kind == ExtensionResourceKind.GROUP && it.id == ref.id }
            (main?.tiles ?: group?.tiles).orEmpty().forEach { tile -> text(root, "tiles.${tile.id}.template", tile.template) }
        }
    }
}
