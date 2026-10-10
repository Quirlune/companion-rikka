package dev.gray.companion.core

/** Pure validation shared by saved definitions and the action's captured execution world. */
object CardDefinitions {
    private val fieldKey = Regex("[\\p{L}_][\\p{L}\\p{N}_-]{0,63}")

    fun validate(definition: CardDefinition, path: String = "cardDefinition",
                 executionVersion: Int = ExecutionSemantics.CURRENT): List<Issue> = buildList {
        if (definition.title.isBlank() || definition.title.length > 128)
            add(Issue("$path.title", "卡片标题须为 1 到 128 个字符"))
        if (definition.minimumWidthDp !in 120..1200)
            add(Issue("$path.minimumWidthDp", "紧凑显示断点须在 120 到 1200 dp 之间"))
        if (definition.fields.size !in 1..32) add(Issue("$path.fields", "卡片须有 1 到 32 个字段"))
        if (definition.fields.map { it.key }.distinct().size != definition.fields.size)
            add(Issue("$path.fields", "卡片字段标识不能重复"))
        if (executionVersion < 22 && (definition.sections.isNotEmpty() || definition.elements.any { it.sectionId.isNotEmpty() }))
            add(Issue("$path.sections", "折叠分区需要执行版本 22"))
        if (definition.sections.size > 32 || definition.sections.map { it.id }.distinct().size != definition.sections.size)
            add(Issue("$path.sections", "折叠分区须有唯一标识且不能超过 32 个"))
        definition.sections.forEachIndexed { index, section ->
            if (!Regex("[A-Za-z_][A-Za-z0-9_-]{0,127}").matches(section.id) ||
                definition.elements.ifEmpty { definition.fields.map { CardElement(it.key, it.key) } }.any { it.id == section.id })
                add(Issue("$path.sections.$index.id", "分区标识无效或与显示元素重复"))
            if (section.title.isBlank() || section.title.length > 128)
                add(Issue("$path.sections.$index.title", "分区标题须为 1 到 128 个字符"))
        }
        definition.elements.forEachIndexed { index, element ->
            if (element.sectionId.isNotEmpty() && definition.sections.none { it.id == element.sectionId })
                add(Issue("$path.elements.$index.sectionId", "引用的折叠分区不存在"))
        }
        if (definition.dataMode == CardDataMode.XML) {
            if (executionVersion < 20) add(Issue("$path.dataMode", "XML 卡片需要执行版本 20"))
            XmlCardData.schemaProblem(definition)?.let { add(Issue("$path.xmlSample", it)) }
        }
        if (executionVersion >= 19) {
            CardMarkup.validate(definition.css)?.let { add(Issue("$path.css", it)) }
            if (definition.elements.size > 128 || definition.elements.map { it.id }.distinct().size != definition.elements.size)
                add(Issue("$path.elements", "显示元素须有唯一标识且不能超过 128 个"))
            definition.elements.forEachIndexed { index, element ->
                if (!Regex("[A-Za-z_][A-Za-z0-9_\\-]{0,127}").matches(element.id) || element.fieldKey !in definition.fields.map { it.key })
                    add(Issue("$path.elements.$index", "显示元素标识或字段引用无效"))
            }
            if (definition.defaultRegion != null && definition.defaultRegion !in CardDisplays.allowedRegions(definition.presentation))
                add(Issue("$path.defaultRegion", "卡片默认区域与显示品类不兼容"))
            definition.condition?.expression?.takeIf { it.isNotBlank() }?.let { expression ->
                ConditionExpressions.validate(expression)?.let { add(Issue("$path.condition", it)) }
            }
        }
        definition.fields.forEachIndexed { index, field ->
            val fieldPath = "$path.fields.$index"
            if (!(if (executionVersion >= 20) XmlCardData.validPath(field.key) else fieldKey.matches(field.key)))
                add(Issue("$fieldPath.key", "字段标识须为有效文字标签或点分路径，标签最多 64 字符、路径最多 256 字符"))
            if (field.title.isBlank() || field.title.length > 128)
                add(Issue("$fieldPath.title", "字段名称须为 1 到 128 个字符"))
            if (field.source.length > 65536) add(Issue("$fieldPath.source", "字段来源模板过长"))
            val scan = MacroTemplateTokens.scan(field.source, executionVersion)
            scan.diagnostics.forEach { add(Issue("$fieldPath.source", it.message)) }
            scan.tokens.filter { it.closed }.forEach { token ->
                macroExpressionError(token.expression.trim(), executionVersion)?.let { add(Issue("$fieldPath.source", it)) }
            }
            SafePatterns.validate(field.pattern, field.captureGroup)?.let { add(Issue("$fieldPath.pattern", it)) }
            if (!field.minimum.isFinite() || !field.maximum.isFinite() ||
                (field.kind == CardFieldKind.PROGRESS && field.maximum <= field.minimum))
                add(Issue("$fieldPath.maximum", "字段范围须为有限数字；进度上限须大于下限"))
            if (field.compactLimit !in 1..32) add(Issue("$fieldPath.compactLimit", "紧凑列表项数须在 1 到 32 之间"))
        }
    }

    fun validateNode(node: WorkflowNode, path: String,
                     executionVersion: Int = ExecutionSemantics.CURRENT): List<Issue> = buildList {
        if (node.actionId != "card.define") return@buildList
        val definition = node.cardDefinition
        if (definition == null) add(Issue("$path.cardDefinition", "定义卡片需要字段配置"))
        else addAll(validate(definition, "$path.cardDefinition", executionVersion))
        if (node.visible) add(Issue("$path.visible", "卡片结果由用户放置，不能将原始数据直接显示到聊天"))
        if (node.outputProcessing.isNotEmpty()) add(Issue("$path.outputProcessing", "卡片结果不能再进行文本输出处理"))
        if (node.regexOutputCopy.enabled) add(Issue("$path.regexOutputCopy", "卡片结果不能再创建正则输出副本"))
    }
}

/** Presentation reads accepted snapshots only; it never re-extracts from live source macros. */
object CardDisplays {
    private const val MAX_TEXT = 16384
    private const val MAX_ITEMS = 256
    private const val MAX_SNAPSHOT = 1048576

    fun allowedRegions(presentation: CardPresentation): List<CardRegion> = when (presentation) {
        CardPresentation.CARD -> listOf(CardRegion.CHAT, CardRegion.SIDEBAR, CardRegion.INPUT, CardRegion.MENU)
        CardPresentation.SIDE_PANEL -> listOf(CardRegion.SIDEBAR, CardRegion.MENU)
        CardPresentation.STRIP -> listOf(CardRegion.CHAT, CardRegion.INPUT, CardRegion.MENU)
    }

    /** A floor-bound card needs the accepted BODY from the latest actual user turn. */
    fun currentPrimaryBody(library: Library, session: Session): ChatMessage? {
        val userIndex = session.messages.indexOfLast { !it.deleted && it.role == Role.USER &&
            it.outputSlot == OutputSlot.BODY }
        if (userIndex < 0) return null
        val user = session.messages[userIndex]
        val effective = CompletedWorlds.executionLibrary(library, session)
        val main = effective.mainCalls.firstOrNull { it.id == session.mainCallId } ?: return null
        val primary = DefaultCallRules.primary(main) ?: return null
        return session.messages.drop(userIndex + 1).lastOrNull { !it.deleted &&
            it.role == Role.ASSISTANT && it.outputSlot == OutputSlot.BODY &&
            it.turnId == user.id && it.workflowId == primary.id && it.text.isNotBlank() }
    }

    /** Never attach a card to another visible reply or a changed BODY revision. */
    fun belongsBelowBody(resolved: ResolvedCard, body: ChatMessage,
                         allowLegacy: Boolean = true): Boolean {
        val snapshot = resolved.snapshot ?: return false
        if (resolved.acceptedTurnId == null || resolved.acceptedTurnId != body.turnId) return false
        return when (snapshot.formatVersion) {
            2, 3 -> snapshot.bodySource != null && snapshot.bodySource == HistoryMemory.source(body)
            1 -> allowLegacy && !resolved.valueCopiedFromEarlierVersion && snapshot.bodySource == null &&
                (body.activeTextRevisionId ?: body.id) == body.id
            else -> false
        }
    }

    fun configured(library: Library, session: Session): List<ConfiguredCard> =
        configuredIn(CompletedWorlds.executionLibrary(library, session), session).filter {
            PluginParticipation.cardVisible(library, session, it.resourceId, it.nodeId)
        }

    private fun configuredIn(library: Library, session: Session): List<ConfiguredCard> = buildList {
        fun addWorkflows(resourceId: String, workflows: List<Workflow>) {
            workflows.forEach { workflow -> workflow.nodes.forEach { node ->
                val definition = node.cardDefinition
                if (node.actionId == "card.define" && definition != null)
                    add(ConfiguredCard(resourceId, workflow.id, node.id, definition.title,
                        workflow.enabled && node.enabled, definition, node.outputKey))
            } }
        }
        val main = library.mainCalls.firstOrNull { it.id == session.mainCallId }
        fun addResources(resourceId: String, cards: List<CardResource>) {
            cards.forEach { card -> add(ConfiguredCard(resourceId, "", card.id, card.definition.title,
                card.enabled, card.definition, "", card.sourceTemplate)) }
        }
        main?.let { addResources(it.id, it.cards); addWorkflows(it.id, it.configuredWorkflows()) }
        library.loadedCallGroups(session, main).forEach { addResources(it.id, it.cards); addWorkflows(it.id, it.workflows) }
    }

    fun effectivePlacements(library: Library, session: Session, body: ChatMessage? = null): List<CardPlacement> {
        val view = if (body == null) session else floorSession(session, body)
            ?: if (body.id == currentPrimaryBody(library, session)?.id && (body.activeTextRevisionId ?: body.id) == body.id) session else return emptyList()
        return configured(library, view).mapIndexedNotNull { index, card ->
            val preference = session.cardPlacements.firstOrNull { it.nodeId == card.nodeId }
            if (card.definition.userAdjustable && preference != null &&
                preference.region in allowedRegions(card.definition.presentation)) preference
            else card.definition.defaultRegion?.let { CardPlacement(card.nodeId, it, index) }
        }
    }

    /** Reconstruct only the selected floor's accepted branch, never borrowing today's runtime. */
    private fun floorSession(session: Session, body: ChatMessage): Session? {
        val record = session.turnRecords.firstOrNull { it.userMessageId == body.turnId } ?: return null
        val variant = record.variants.firstOrNull { it.id == record.selectedVariantId } ?: return null
        val accepted = variant.replies.firstOrNull { it.id == body.id }
            ?: session.messages.firstOrNull { it.id == body.id && it.id in variant.replyIds }
            ?: return null
        // Compacted variants retain reply IDs only, so an edited revision has no accepted source proof.
        if (variant.replies.none { it.id == body.id } && (body.activeTextRevisionId ?: body.id) != body.id) return null
        if (HistoryMemory.source(accepted) != HistoryMemory.source(body) ||
            (body.activeTextRevisionId ?: body.id) != (accepted.activeTextRevisionId ?: accepted.id)) return null
        if (record.checkpoint.macroLengths.any { (key, count) -> session.macroHistory[key].orEmpty().size < count }) return null
        val history = (record.checkpoint.macroLengths.keys + variant.macroAppends.keys).associateWith { key ->
            session.macroHistory[key].orEmpty().take(record.checkpoint.macroLengths[key] ?: 0) + variant.macroAppends[key].orEmpty()
        }
        val end = session.messages.indexOfFirst { it.id == body.id }
        if (end < 0) return null
        return CompletedWorlds.restore(session, variant.after).copy(macroHistory = history,
            messages = session.messages.take(end + 1), pendingRun = null)
    }

    fun resolve(library: Library, session: Session, body: ChatMessage? = null): List<ResolvedCard> {
        val latest = currentPrimaryBody(library, session)
        val view = if (body == null) session else floorSession(session, body)
            ?: if (body.id == latest?.id && (body.activeTextRevisionId ?: body.id) == body.id) session else return emptyList()
        val effective = CompletedWorlds.executionLibrary(library, view)
        return configuredIn(effective, view).filter { it.enabled &&
            PluginParticipation.cardVisible(library, view, it.resourceId, it.nodeId) }.mapNotNull { card ->
            if (body != null && card.definition.floorScope == CardFloorScope.LATEST && body.id != latest?.id) return@mapNotNull null
            val observedResults = linkedSetOf<Pair<String, String>>()
            val visibilityResolver = MacroResolver(effective, view, onResultRead = { address, selector ->
                observedResults += address to selector
            })
            if (!conditionMatches(card.definition.condition, view.state, view.results, readMacro = visibilityResolver::read)) return@mapNotNull null
            if (card.sourceTemplate != null) {
                try {
                    val floor = body ?: latest
                    val source = visibilityResolver.render(card.sourceTemplate)
                    val fields = extractFields(card.definition, source, visibilityResolver::render)
                    if (visibilityResolver.issues.any { it.error }) return@mapNotNull ResolvedCard(card, error = "卡片来源引用不可用")
                    // Observe actual reads, including typed selectors and nested entry templates.
                    if (floor != null && observedResults.any { (address, selector) ->
                            val accepted = MacroReferences.selectVersion(effective, view, address, selector)
                            selector == "latest" && (accepted == null || accepted.turnId != floor.turnId || accepted.valueOrigin != null)
                        }) return@mapNotNull null
                    val snapshot = makeSnapshot(card.nodeId, card.definition, fields, 3,
                        (body ?: latest)?.let { HistoryMemory.source(it) })
                    snapshotProblem(snapshot)?.let { throw IllegalArgumentException(it) }
                    if (card.definition.hideWhenEmpty && fields.all { it.value.isBlank() && it.items.isEmpty() }) return@mapNotNull null
                    return@mapNotNull ResolvedCard(card, snapshot, acceptedTurnId = (body ?: latest)?.turnId)
                } catch (error: IllegalArgumentException) { return@mapNotNull ResolvedCard(card, error = error.message ?: "卡片数据无效") }
            }
            val address = "result.${card.outputKey}"
            val version = MacroReferences.currentVersion(effective, view, address)
            val copied = version?.valueOrigin != null
            if (version?.enabled == false) return@mapNotNull null
            val resolver = MacroResolver(effective, view)
            val text = resolver.render("{{$address}}")
            if (text.isEmpty()) return@mapNotNull ResolvedCard(card, waiting = true,
                acceptedTurnId = version?.turnId, valueCopiedFromEarlierVersion = copied)
            val origin = version?.valueOrigin ?: version?.origin ?: view.resultOrigins[card.outputKey]
            if (origin == null || origin.resourceId != card.resourceId || origin.workflowId != card.workflowId || origin.nodeId != card.nodeId)
                return@mapNotNull ResolvedCard(card, error = "卡片结果不属于当前行为",
                    acceptedTurnId = version?.turnId, valueCopiedFromEarlierVersion = copied)
            if (resolver.issues.any { it.error }) return@mapNotNull ResolvedCard(card,
                error = "卡片结果引用不可用", acceptedTurnId = version?.turnId,
                valueCopiedFromEarlierVersion = copied)
            try {
                val accepted = decodeSnapshot(text, card.nodeId)
                if (accepted.formatVersion >= 3 && accepted.hideWhenEmpty && accepted.fields.all { it.value.isBlank() && it.items.isEmpty() }) return@mapNotNull null
                ResolvedCard(card, accepted, acceptedTurnId = version?.turnId, valueCopiedFromEarlierVersion = copied)
            }
            catch (_: IllegalArgumentException) { ResolvedCard(card, error = "卡片结果格式无效",
                acceptedTurnId = version?.turnId, valueCopiedFromEarlierVersion = copied) }
        }
    }

    fun encodeSnapshot(snapshot: CardSnapshot): String {
        snapshotProblem(snapshot)?.let { throw IllegalArgumentException(it) }
        val text = LibraryFormat.json.encodeToString(CardSnapshot.serializer(), snapshot)
        require(text.length <= MAX_SNAPSHOT) { "卡片结果过长" }
        return text
    }

    fun decodeSnapshot(text: String, expectedNodeId: String? = null): CardSnapshot {
        require(text.length <= MAX_SNAPSHOT) { "卡片结果过长" }
        val snapshot = try { LibraryFormat.json.decodeFromString(CardSnapshot.serializer(), text) }
        catch (_: Exception) { throw IllegalArgumentException("卡片结果 JSON 或字段无效") }
        snapshotProblem(snapshot)?.let { throw IllegalArgumentException(it) }
        require(expectedNodeId == null || snapshot.nodeId == expectedNodeId) { "卡片结果行为标识不一致" }
        return snapshot
    }

    /** MANUAL recovery may correct values, but cannot forge a different presentation contract. */
    fun validateOutcome(node: WorkflowNode, text: String, context: ActionContext? = null): String? = try {
        val definition = requireNotNull(node.cardDefinition)
        val snapshot = decodeSnapshot(text, node.id)
        require(snapshot.title == definition.title && snapshot.minimumWidthDp == definition.minimumWidthDp &&
            snapshot.presentation == definition.presentation &&
            snapshot.fields.map { it.presentation() } == definition.fields.map { it.presentation() })
        if (snapshot.formatVersion >= 3) require(snapshot == makeSnapshot(snapshot.nodeId, definition, snapshot.fields, 3, snapshot.bodySource))
        if (context != null && ExecutionSemantics(context.executionVersion).floorBoundCardSources) {
            require(snapshot.formatVersion == if (context.executionVersion >= 19) 3 else 2)
            val source = currentPrimaryBody(requireNotNull(context.library), requireNotNull(context.session))
                ?.let { HistoryMemory.source(it) }
            require(snapshot.bodySource == source)
        }
        null
    } catch (_: Exception) { "卡片结果须符合此行为的字段类型和呈现配置" }

    fun define(context: ActionContext): ActionOutcome {
        CardDefinitions.validateNode(context.node, "node.${context.node.id}", context.executionVersion)
            .firstOrNull { it.error }?.let { throw IllegalArgumentException(it.message) }
        val definition = requireNotNull(context.node.cardDefinition)
        val resolver = MacroResolver(requireNotNull(context.library), requireNotNull(context.session),
            context.input, context.executionVersion)
        val fields = extractFields(definition, context.input, resolver::render)
        if (resolver.issues.any { it.error }) throw IllegalArgumentException("卡片字段来源模板无效")
        val floorBound = ExecutionSemantics(context.executionVersion).floorBoundCardSources
        val snapshot = makeSnapshot(context.node.id, definition, fields,
            if (context.executionVersion >= 19) 3 else if (floorBound) 2 else 1,
            if (floorBound) currentPrimaryBody(requireNotNull(context.library), requireNotNull(context.session))?.let { HistoryMemory.source(it) } else null)
        snapshotProblem(snapshot)?.let { throw OutputFormatException(it) }
        return ActionOutcome(encodeSnapshot(snapshot))
    }

    fun extractFields(definition: CardDefinition, input: String,
                      renderTemplate: (String) -> String = { it }): List<CardSnapshotField> {
        if (definition.dataMode == CardDataMode.XML) return XmlCardData.extract(definition, input)
        return definition.fields.map { field ->
            val source = if (field.source.isBlank()) input else renderTemplate(field.source)
            val items = if (field.kind == CardFieldKind.LIST)
                SafePatterns.extractAll(field.pattern, source, field.captureGroup).filter { it.isNotEmpty() }
            else emptyList()
            val extracted = if (field.kind == CardFieldKind.LIST) ""
                else SafePatterns.extract(field.pattern, source, field.captureGroup).orEmpty()
            val value = if (field.kind in setOf(CardFieldKind.NUMBER, CardFieldKind.PROGRESS) && extracted.isNotBlank()) {
                if (extracted.length > MAX_TEXT) throw OutputFormatException("卡片字段内容过长")
                val number = extracted.trim().toBigDecimalOrNull()?.takeIf { it.toDouble().isFinite() }
                    ?: throw OutputFormatException("卡片字段“${field.title}”需要有限数字")
                val normalized = number.stripTrailingZeros()
                if (normalized.scale() !in -MAX_TEXT..MAX_TEXT) throw OutputFormatException("卡片数值展开后过长")
                normalized.toPlainString()
            } else if (field.kind in setOf(CardFieldKind.NUMBER, CardFieldKind.PROGRESS)) "" else extracted
            CardSnapshotField(field.key, field.title, field.kind, value, items,
                field.minimum, field.maximum, field.compactLimit)
        }
    }

    private fun makeSnapshot(id: String, definition: CardDefinition, fields: List<CardSnapshotField>, version: Int,
                         body: HistorySourceRef?): CardSnapshot = CardSnapshot(
        formatVersion = version, nodeId = id, title = definition.title, minimumWidthDp = definition.minimumWidthDp,
        fields = fields, presentation = definition.presentation, bodySource = body).let { base ->
        if (version < 3) base else base.copy(css = definition.css, elements = definition.elements,
            defaultRegion = definition.defaultRegion, userAdjustable = definition.userAdjustable,
            autoAdapt = definition.autoAdapt, floorScope = definition.floorScope,
            hideWhenEmpty = definition.hideWhenEmpty, condition = definition.condition, sections = definition.sections)
    }

    private fun snapshotProblem(snapshot: CardSnapshot): String? {
        if (snapshot.formatVersion !in 1..3 || snapshot.nodeId.isBlank() ||
            (snapshot.formatVersion == 1 && snapshot.bodySource != null))
            return "卡片结果版本或行为标识无效"
        val definition = CardDefinition(snapshot.title, snapshot.minimumWidthDp, snapshot.fields.map {
            CardFieldDefinition(it.key, it.title, kind = it.kind, minimum = it.minimum,
                maximum = it.maximum, compactLimit = it.compactLimit)
        }, snapshot.presentation, snapshot.css, snapshot.elements, snapshot.defaultRegion,
            snapshot.userAdjustable, snapshot.autoAdapt, snapshot.floorScope, snapshot.hideWhenEmpty, snapshot.condition,
            sections = snapshot.sections)
        CardDefinitions.validate(definition).firstOrNull { it.error }?.let { return it.message }
        for (field in snapshot.fields) {
            if (field.value.length > MAX_TEXT || field.items.size > MAX_ITEMS || field.items.any { it.length > MAX_TEXT })
                return "卡片字段内容过长"
            if (field.kind == CardFieldKind.LIST) {
                if (field.value.isNotEmpty()) return "列表字段只能包含列表项"
            } else if (field.items.isNotEmpty()) return "非列表字段不能包含列表项"
            if (field.kind in setOf(CardFieldKind.NUMBER, CardFieldKind.PROGRESS) && field.value.isNotEmpty() &&
                field.value.trim().toBigDecimalOrNull()?.toDouble()?.isFinite() != true) return "数值字段需要有限数字"
        }
        return null
    }

    private fun CardSnapshotField.presentation() = CardFieldDefinition(key, title, kind = kind,
        minimum = minimum, maximum = maximum, compactLimit = compactLimit)
    private fun CardFieldDefinition.presentation() = copy(source = "", pattern = "", captureGroup = 0, xmlPath = "")
}
