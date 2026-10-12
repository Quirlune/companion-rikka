package dev.gray.companion.core

object LibraryValidator {
    fun validate(library: Library, executionVersion: Int = ExecutionSemantics.CURRENT): List<Issue> {
        val semantics = ExecutionSemantics(executionVersion)
        val issues = mutableListOf<Issue>()
        issues += ExecutionResources.validate(library, executionVersion)
        if (library.schemaVersion != LibraryFormat.CURRENT_VERSION) issues += Issue("library.schemaVersion", "不支持的资源版本")
        issues += NativeProviderConfiguration.validate(library)
        checkMetadata(library, issues)
        issues += ExtensionCommands.validate(library)
        issues += PluginCodes.validateCode(library)
        if (semantics.nativeMemoryPlugins) issues += MemoryPlugins.validate(library)
        if (semantics.globalMemory) issues += GlobalMemory.validate(library)
        library.sessions.forEach { session -> session.enabledExtensionIds.forEach { id ->
            if ((session.executableWorld?.library ?: library).extensions.none { it.id == id }) issues += Issue("session.${session.id}.enabledExtensionIds", "启用的插件不存在")
        } }
        if (semantics.reasoningArchives)
            library.reasoningParsing.validationError()?.let { issues += Issue("library.reasoningParsing", it) }
        fun unique(values: List<String>, path: String) {
            values.filter { it.isBlank() }.forEach { issues += Issue(path, "ID 或名称不能为空") }
            values.filter { it.isNotBlank() }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach {
                issues += Issue(path, "重复值：$it")
            }
        }
        unique(library.characters.map { it.id } + library.packages.map { it.id } + library.mainCalls.map { it.id } + library.groups.map { it.id } + library.profiles.map { it.id } + library.connections.map { it.id } + library.sessions.map { it.id } + library.personas.map { it.id } + library.extensions.map { it.id }, "library.ids")
        unique(library.chatFolders.map { it.id }, "library.chatFolderIds")
        unique(library.savedChats.map { it.id }, "library.savedChatIds")
        library.chatFolders.forEach { if (it.name.isBlank()) issues += Issue("chatFolder.${it.id}", "收藏文件夹名称不能为空") }
        library.savedChats.forEach { saved ->
            if (library.chatFolders.none { it.id == saved.folderId }) issues += Issue("savedChat.${saved.id}", "收藏文件夹不存在")
        }
        if (library.user.name.isBlank()) issues += Issue("library.user.name", "用户名称不能为空")
        if (library.user.avatar != null && library.user.avatar.decodedBytes() == null)
            issues += Issue("library.user.avatar", "头像必须是 32 KB 以内的 JPEG 或 PNG 图片")
        library.personas.forEach { persona ->
            val path = "persona.${persona.id}"
            if (persona.name.isBlank()) issues += Issue("$path.name", "用户身份名称不能为空")
            if (persona.avatar != null && persona.avatar.decodedBytes() == null)
                issues += Issue("$path.avatar", "头像必须是 32 KB 以内的 JPEG 或 PNG 图片")
        }
        if (library.selectedPersonaId != null && library.personas.none { it.id == library.selectedPersonaId })
            issues += Issue("library.selectedPersonaId", "所选用户身份不存在")
        unique(library.characters.map { it.namespace } + library.packages.map { it.namespace }, "library.namespaces")
        val address = Regex("[\\p{L}_][\\p{L}\\p{N}_-]*")
        (library.characters.map { "character.${it.id}" to it.namespace } + library.packages.map { "pack.${it.id}" to it.namespace }).forEach { (path, namespace) ->
            if (!address.matches(namespace)) issues += Issue(path, "命名空间只能使用文字、数字、下划线和连字符，且不能以数字开头")
        }
        (library.characters.map { "character.${it.id}" to it.name } + library.packages.map { "pack.${it.id}" to it.name } +
            library.mainCalls.map { "main.${it.id}" to it.name } + library.groups.map { "group.${it.id}" to it.name } +
            library.profiles.map { "profile.${it.id}" to it.name } + library.connections.map { "connection.${it.id}" to it.name }).forEach { (path, name) ->
            if (name.isBlank()) issues += Issue(path, "显示名称不能为空")
        }
        val allEntries = library.characters.flatMap { it.entries } + library.packages.flatMap { it.entries }
        unique(allEntries.map { it.id }, "library.entryIds")
        allEntries.forEach { entry ->
            if (!address.matches(entry.key)) issues += Issue("entry.${entry.id}", "条目键不能用于宏地址")
            checkCondition(entry.condition, "entry.${entry.id}.condition", issues)
            if (entry.isTemplate) checkTemplate(entry.text, "entry.${entry.id}.text", issues, executionVersion)
        }
        library.characters.forEach { card ->
            if (card.avatar != null && card.avatar.decodedBytes() == null) issues += Issue("character.${card.id}.avatar", "头像必须是 32 KB 以内的 JPEG 或 PNG 图片")
            if (card.boundPersonaId != null && library.personas.none { it.id == card.boundPersonaId })
                issues += Issue("character.${card.id}.boundPersonaId", "绑定的用户身份不存在")
            unique(card.entries.map { it.key }, "character.${card.id}.entries")
            if (card.entries.any { it.key == "name" }) issues += Issue("character.${card.id}.entries", "name 是角色名称保留键")
            checkTemplate(card.greeting, "character.${card.id}.greeting", issues, executionVersion)
            card.alternateGreetings.forEachIndexed { index, greeting ->
                checkTemplate(greeting, "character.${card.id}.alternateGreetings.$index", issues, executionVersion)
            }
            card.boundPackageIds.forEach { if (library.packages.none { pack -> pack.id == it }) issues += Issue("character.${card.id}", "绑定的提示词包不存在") }
            card.boundGroupIds.forEach { id -> if (library.groups.none { it.id == id }) issues += Issue("character.${card.id}.boundGroupIds", "绑定的调用组不存在") }
        }
        library.packages.forEach { unique(it.entries.map { entry -> entry.key }, "pack.${it.id}.entries") }
        library.groups.forEach { group ->
            group.boundPackageIds.forEach { if (library.packages.none { pack -> pack.id == it }) issues += Issue("group.${group.id}", "绑定的提示词包不存在") }
        }
        fun checkSession(session: Session, path: String) {
            issues += ConversationPaths.validate(session).map { issue ->
                issue.copy(path = issue.path.replaceFirst("session.${session.id}", path))
            }
            // Folder membership is live UI metadata; archived snapshots may retain an old folder ID.
            if (path.startsWith("session.") && session.folderId != null && library.chatFolders.none { it.id == session.folderId })
                issues += Issue("$path.folderId", "聊天文件夹不存在")
            issues += CardLayoutCommands.validate(session.cardPlacements, "$path.cardPlacements")
            session.userMacros.forEach { (shortcut, definition) ->
                if (shortcut != definition.shortcut) issues += Issue("$path.userMacros.$shortcut", "用户宏键与快捷词不一致")
                issues += UserInputMacros.validate(definition, "$path.userMacros.$shortcut")
            }
            checkMemory(session.historyMemory, "$path.historyMemory", issues)
            session.currentOnlyMacros.forEach { (key, head) ->
                val at = "$path.currentOnlyMacros.$key"
                val static = key.startsWith("character.") || key.startsWith("pack.")
                if (canonicalWriteAddress(key) != key || head.version < 1 ||
                    (static && head.text == null) || (!static && head.text != null) ||
                    (head.gap.firstTurn != null && head.gap.firstTurn < 0))
                    issues += Issue(at, "当前宏的地址、版本或覆盖结构无效")
            }
            val sessionResources = session.executableWorld?.library ?: library
            if (session.personaId != null && sessionResources.personas.none { it.id == session.personaId })
                issues += Issue("$path.personaId", "会话用户身份不存在")
            if (session.characterId.isNotBlank() && sessionResources.characters.none { it.id == session.characterId }) issues += Issue(path, "角色卡不存在")
            if (session.mainCallId.isNotBlank() && sessionResources.mainCalls.none { it.id == session.mainCallId }) issues += Issue(path, "主调用不存在")
            session.packageIds.forEach { id -> if (sessionResources.packages.none { it.id == id }) issues += Issue(path, "提示词包不存在") }
            session.groupIds.forEach { id -> if (sessionResources.groups.none { it.id == id }) issues += Issue(path, "调用组不存在") }
            session.historyDigests.forEach { (mainId, digest) ->
                if (sessionResources.mainCalls.none { it.id == mainId }) issues += Issue("$path.historyDigests.$mainId", "历史摘要对应的主调用不存在")
                val body = session.messages.filter { it.outputSlot == OutputSlot.BODY && !it.deleted }
                if (digest.coveredFloors !in 1..body.size || body[digest.coveredFloors - 1].id != digest.throughMessageId)
                    issues += Issue("$path.historyDigests.$mainId", "历史摘要覆盖范围与原消息不一致")
            }
        }
        library.sessions.forEach { checkSession(it, "session.${it.id}") }
        library.savedChats.forEach { checkSession(it.snapshot, "savedChat.${it.id}.snapshot") }
        (library.sessions.map { "session.${it.id}" to it } + library.savedChats.map { "savedChat.${it.id}.snapshot" to it.snapshot })
            .forEach { (path, session) ->
                val main = (session.executableWorld?.library ?: library).mainCalls.firstOrNull { it.id == session.mainCallId } ?: return@forEach
                val floors = session.messages.count { it.outputSlot == OutputSlot.BODY && !it.deleted }
                if (!HistoryContext.rangeValid(main.history.range, floors))
                    issues += Issue("$path.history", "所选历史范围起点晚于终点")
            }
        if (library.selectedProfileId != null && library.profiles.none { it.id == library.selectedProfileId }) issues += Issue("library.selectedProfileId", "所选模型不存在")
        issues += AutomaticOptions.validate(library)
        if (library.selectedSessionId != null && library.sessions.none { it.id == library.selectedSessionId }) issues += Issue("library.selectedSessionId", "所选会话不存在")
        library.mainCalls.forEach { main ->
            issues += DefaultCallRules.issues(main)
            if (main.avatar != null && main.avatar.decodedBytes() == null)
                issues += Issue("main.${main.id}.avatar", "头像必须是 32 KB 以内的 JPEG 或 PNG 图片")
            main.boundPackageIds.forEach { id ->
                if (library.packages.none { it.id == id }) issues += Issue("main.${main.id}.boundPackageIds", "绑定的提示词包不存在")
            }
            if (main.primaryWorkflowId != null && main.workflows.none { it.id == main.primaryWorkflowId })
                issues += Issue("main.${main.id}.primaryWorkflowId", "默认调用不存在")
            main.boundGroupIds.forEach { id -> if (library.groups.none { it.id == id }) issues += Issue("main.${main.id}.boundGroupIds", "绑定的调用组不存在") }
            main.bodySourceTileId?.takeIf { ExecutionSemantics(executionVersion).legacyTiles }?.let { sourceId ->
                val source = (main.tiles + library.groups.flatMap { it.tiles }).firstOrNull { it.id == sourceId }
                val path = "main.${main.id}.bodySourceTileId"
                when {
                    source == null -> issues += Issue(path, "正文显示模板不存在；使用原正文", error = false)
                    !source.enabled || source.placement != MacroTilePlacement.CHAT ||
                        source.kind !in setOf(MacroTileKind.TEXT, MacroTileKind.HTML) ->
                        issues += Issue(path, "正文来源须为已启用的聊天文本或 HTML 模板；使用原正文", error = false)
                    source.kind == MacroTileKind.HTML -> {
                        if (main.bodyTextTemplate.isBlank()) issues += Issue("main.${main.id}.bodyTextTemplate", "HTML 正文须指定供历史、复制与导出使用的文本模板；使用原正文", error = false)
                        val displayIssues = mutableListOf<Issue>()
                        checkTemplate(main.bodyTextTemplate, "main.${main.id}.bodyTextTemplate", displayIssues, executionVersion)
                        issues += displayIssues.map { it.copy(error = false) }
                    }
                }
            }
            checkHistory(main, issues, executionVersion)
        }
        val cards = library.mainCalls.flatMap { it.cards } + library.groups.flatMap { it.cards }
        unique(cards.map { it.id } + (library.mainCalls.flatMap { it.configuredWorkflows() } +
            library.groups.flatMap { it.workflows }).flatMap { it.nodes }.map { it.id }, "library.cardIds")
        cards.forEach { card ->
            val path = "card.${card.id}"
            if (card.id.isBlank()) issues += Issue(path, "卡片 ID 不能为空")
            checkTemplate(card.sourceTemplate, "$path.sourceTemplate", issues, executionVersion)
            issues += CardDefinitions.validate(card.definition, "$path.definition", executionVersion)
        }
        val tiles = if (ExecutionSemantics(executionVersion).legacyTiles)
            library.mainCalls.flatMap { it.tiles } + library.groups.flatMap { it.tiles } else emptyList()
        unique(tiles.map { it.id }, "library.tileIds")
        tiles.forEach { tile ->
            val path = "tile.${tile.id}"
            checkTemplate(tile.template, "$path.template", issues, executionVersion)
            SafePatterns.validate(tile.pattern, tile.captureGroup)?.let { issues += Issue("$path.pattern", it) }
            issues += HtmlTemplate.validate(tile)
            if (tile.title.isBlank()) issues += Issue(path, "磁贴名称不能为空")
            if (!tile.minimum.isFinite() || !tile.maximum.isFinite() || (tile.kind == MacroTileKind.PROGRESS && tile.maximum <= tile.minimum))
                issues += Issue(path, "进度上下限必须是有效数字，且上限大于下限")
            if (tile.kind == MacroTileKind.STATUS && tile.fields.isEmpty()) issues += Issue("$path.fields", "状态磁贴至少需要一个字段")
            tile.fields.forEachIndexed { index, field ->
                val at = "$path.fields.$index"
                if (!StatusFields.validKey(field.key)) issues += Issue("$at.key", "状态字段 XML 路径无效")
                if (field.title.isBlank()) issues += Issue("$at.title", "状态字段名称不能为空")
                if (field.kind !in setOf(MacroTileKind.TEXT, MacroTileKind.NUMBER, MacroTileKind.PROGRESS))
                    issues += Issue("$at.kind", "状态字段只支持文字、数值或进度")
                if (!field.minimum.isFinite() || !field.maximum.isFinite() ||
                    field.kind == MacroTileKind.PROGRESS && field.maximum <= field.minimum)
                    issues += Issue(at, "状态字段数值边界无效")
            }
            if (tile.style.fontSize !in 8..72 || tile.style.cornerRadius !in 0..64) issues += Issue(path, "磁贴字号或圆角超出范围")
            if (tile.style.widthPercent !in setOf(50, 100)) issues += Issue(path, "磁贴宽度须为半宽或全宽")
            listOf(tile.style.foreground, tile.style.background).filter { it.isNotBlank() }.forEach {
                if (!Regex("#(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})").matches(it)) issues += Issue(path, "样式颜色须为 #RRGGBB 或 #AARRGGBB")
            }
        }
        library.connections.forEach { connection ->
            if (!validHttpsAddress(connection.baseUrl)) issues += Issue("connection.${connection.id}.baseUrl", "API 地址必须是有效的 HTTPS 地址")
            if (connection.maxConcurrentRequests !in 1..2)
                issues += Issue("connection.${connection.id}.maxConcurrentRequests", "并发请求数须为 1 或 2")
            connection.customHeaders.forEachIndexed { index, header ->
                if (!Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+").matches(header.name) ||
                    header.value.any { it == '\r' || it == '\n' || it.code < 32 && it != '\t' })
                    issues += Issue("connection.${connection.id}.customHeaders.$index", "请求头名称或内容无效")
            }
        }
        library.profiles.forEach { profile ->
            if (profile.model.isBlank()) issues += Issue("profile.${profile.id}", "尚未填写模型标识", false)
            if (profile.apiConnectionId != null) {
                if (library.connections.none { it.id == profile.apiConnectionId })
                    issues += Issue("profile.${profile.id}.apiConnectionId", "API 连接不存在")
            } else if (!validHttpsAddress(profile.baseUrl)) issues += Issue("profile.${profile.id}", "模型地址必须是有效的 HTTPS 地址")
            if (profile.temperature != null && !profile.temperature.isFinite())
                issues += Issue("profile.${profile.id}.temperature", "温度必须是有限数字")
            if (profile.topP != null && (!profile.topP.isFinite() || profile.topP !in 0f..1f))
                issues += Issue("profile.${profile.id}.topP", "Top P 必须在 0 到 1 之间")
            if (profile.maxTokens != null && profile.maxTokens <= 0) issues += Issue("profile.${profile.id}", "最大令牌数必须大于零")
            profile.customBody.forEachIndexed { index, field ->
                if (field.key.isBlank()) issues += Issue("profile.${profile.id}.customBody.$index", "自定义请求参数名称不能为空")
            }
        }
        val processorIds = library.mainCalls.map { it.history.processor.workflow.id }.toSet() +
            library.extensions.mapNotNull { definition ->
                MemoryPlugins.processor(library, definition)?.workflow?.id
            }
        val allWorkflows = library.mainCalls.flatMap { it.configuredWorkflows() } + library.groups.flatMap { it.workflows }
        unique(allWorkflows.map { it.id }, "library.workflowIds")
        unique(allWorkflows.distinctBy { it.id }.flatMap { it.nodes }.filter { it.actionId == "card.define" }
            .map { it.id }, "library.cardNodeIds")
        unique(allWorkflows.flatMap { it.nodes }.flatMap { it.blocks }.map { it.id }, "library.blockIds")
        allWorkflows.forEach { workflow ->
            val path = "workflow.${workflow.id}"
            if (workflow.title.isBlank()) issues += Issue(path, "工作流名称不能为空")
            if (workflow.trigger.isBlank()) issues += Issue(path, "触发事件不能为空")
            workflow.afterWorkflowId?.let { target ->
                if (target == workflow.id) issues += Issue("$path.afterWorkflowId", "调用不能由自身完成触发")
                if (allWorkflows.none { it.id == target }) issues += Issue("$path.afterWorkflowId", "完成触发的来源调用不存在")
                if (workflow.id in processorIds) issues += Issue("$path.afterWorkflowId", "历史处理调用不能改用完成触发")
            }
            if (workflow.id in processorIds && workflow.trigger != RuntimeEvents.HISTORY_PROCESS) issues += Issue(path, "历史处理调用须使用 history.process")
            if (workflow.id !in processorIds && workflow.trigger == RuntimeEvents.HISTORY_PROCESS) issues += Issue(path, "history.process 只供历史处理调用使用")
            if (workflow.modelProfileId != null && library.profiles.none { it.id == workflow.modelProfileId })
                issues += Issue("$path.modelProfileId", "工作流模型不存在")
            checkCondition(workflow.condition, "$path.condition", issues)
            checkRecognition(workflow.recognition, "$path.recognition", issues, executionVersion)
            unique(workflow.nodes.map { it.id }, "$path.nodes")
            val ids = workflow.nodes.map { it.id }.toSet()
            val writerCategories = mutableMapOf<String, String>()
            workflow.nodes.forEach { node ->
                val nodePath = "$path.node.${node.id}"
                issues += CardDefinitions.validateNode(node, nodePath, executionVersion)
                if (semantics.reasoningArchives)
                    node.reasoningParsing?.validationError()?.let { issues += Issue("$nodePath.reasoningParsing", it) }
                if (node.title.isBlank()) issues += Issue(nodePath, "节点名称不能为空")
                if (node.actionId.isBlank()) issues += Issue(nodePath, "动作不能为空")
                if (node.actionId == "user.macro.create") {
                    if (!semantics.userInputMacros) issues += Issue(nodePath, "此恢复版本不支持用户输入宏")
                    val definition = node.userMacro
                    if (definition == null) issues += Issue("$nodePath.userMacro", "请先配置用户宏")
                    else issues += UserInputMacros.validate(definition, "$nodePath.userMacro")
                    if (node.regexOutputCopy.enabled)
                        issues += Issue("$nodePath.regexOutputCopy", "创建用户宏行为不生成结果副本")
                }
                if (node.actionId == "event.emit") {
                    val event = node.arguments["event"].orEmpty().trim()
                    val reserved = semantics.isReservedEvent(event)
                    // Old resources may contain a custom name using the newly reserved prefix.
                    // Keep them loadable; the versioned run planner rejects emitting it in v2.
                    if (reserved) issues += Issue("$nodePath.argument.event", "此事件由运行时派发",
                        error = event in RuntimeEvents.reserved)
                }
                if (node.outputKey.isBlank()) issues += Issue(nodePath, "输出键不能为空")
                if (node.generation.profileId != null && library.profiles.none { it.id == node.generation.profileId })
                    issues += Issue("$nodePath.generation.profileId", "节点模型不存在")
                node.generation.temperature?.let { if (!it.isFinite()) issues += Issue("$nodePath.generation.temperature", "温度必须是有限数字") }
                node.generation.topP?.let { if (!it.isFinite() || it !in 0f..1f) issues += Issue("$nodePath.generation.topP", "Top P 必须在 0 到 1 之间") }
                node.generation.maxTokens?.let { if (it <= 0) issues += Issue("$nodePath.generation.maxTokens", "最大令牌数必须大于零") }
                issues += TextProcessing.validate(node.inputProcessing, "$nodePath.inputProcessing", executionVersion)
                issues += TextProcessing.validate(node.outputProcessing, "$nodePath.outputProcessing", executionVersion)
                issues += RegexOutputCopies.validate(node.regexOutputCopy, "$nodePath.regexOutputCopy")
                if (node.regexOutputCopy.enabled && node.regexOutputCopy.outputKey == node.outputKey)
                    issues += Issue("$nodePath.regexOutputCopy.outputKey", "副本与原始输出不能使用同一个宏地址")
                issues += ActionConfigurationRules.outputProcessing(node, nodePath)
                if (node.actionId == "macro.process") {
                    val config = node.macroProcess
                    issues += ActionConfigurationRules.macroProcess(node, nodePath)
                    checkTemplate(config.initial, "$nodePath.macroProcess.initial", issues, executionVersion)
                    issues += TextProcessing.validate(config.steps, "$nodePath.macroProcess.steps", executionVersion)
                }
                checkCondition(node.condition, "$nodePath.condition", issues)
                checkRecognition(node.recognition, "$nodePath.recognition", issues, executionVersion)
                val previousCategory = writerCategories.putIfAbsent(node.outputKey, node.resultCategory)
                if (previousCategory != null && previousCategory != node.resultCategory) issues += Issue(nodePath, "同一个输出宏须使用相同分类")
                val targetAddress = if (node.actionId == "macro.process") canonicalWriteAddress(
                    node.macroProcess.key.ifBlank { node.arguments["key"].orEmpty() })
                else if (node.actionId in setOf("macro.write", "macro.activate")) MacroReferences.canonicalAddress(node.arguments["key"].orEmpty())
                else null
                targetAddress?.takeIf { it.startsWith("result.") }?.substringAfter("result.")?.let { key ->
                    val category = writerCategories.putIfAbsent(key, node.resultCategory)
                    if (category != null && category != node.resultCategory) issues += Issue(nodePath, "同一个输出宏须使用相同分类")
                }
                node.dependsOn.forEach { dependency ->
                    if (dependency !in ids) issues += Issue(nodePath, "依赖节点不存在：$dependency")
                    if (dependency == node.id) issues += Issue(nodePath, "节点不能依赖自身")
                }
                checkTemplate(node.inputTemplate, "$nodePath.input", issues, executionVersion)
                node.blocks.forEach { block ->
                    val blockPath = "$nodePath.block.${block.id}"
                    issues += DefaultPromptSlots.blockIssues(block, blockPath, executionVersion)
                    checkTemplate(block.template, blockPath, issues, executionVersion)
                    issues += TextProcessing.validate(block.processing, "$blockPath.processing", executionVersion)
                    if (containsStructuredHistoryToken(block.template, executionVersion) && !isStructuredHistoryBlock(block.template, executionVersion))
                        issues += Issue(blockPath, "结构化 history 须单独占一个提示块；混合文本请使用 history.text")
                    checkCondition(block.condition, "$blockPath.condition", issues)
                    checkRecognition(block.recognition, "$blockPath.recognition", issues, executionVersion)
                    if (block.depth < 0) issues += Issue("$blockPath.depth", "插入深度不能小于零")
                }
                node.arguments.forEach { (key, value) -> checkTemplate(value, "$nodePath.argument.$key", issues, executionVersion) }
                if (node.actionId == "prompt.inject" && semantics.independentContextOrder) {
                    val order = node.arguments["contextOrder"] ?: "1"
                    if (!order.contains("{{") && order.trim().toIntOrNull()?.let { it >= 1 } != true)
                        issues += Issue("$nodePath.argument.contextOrder", "上下文顺序须为正整数")
                }
                if (node.actionId == "prompt.inject" && node.arguments["targetBlockId"].orEmpty().isNotBlank()) {
                    val blockId = node.arguments.getValue("targetBlockId")
                    val at = "$nodePath.argument.targetBlockId"
                    if (!semantics.anchoredPromptSlots) issues += Issue(at, "此恢复版本不支持指定注入槽")
                    else if (listOf("targetWorkflowId", "targetNodeId", "targetBlockId").none { node.arguments[it].orEmpty().contains("{{") }) {
                        val target = library.mainCalls.any { main -> injectionTarget(main, ActionRegistry.defaults().snapshot(),
                            node.arguments["targetWorkflowId"], node.arguments["targetNodeId"], blockId, executionVersion, requireEnabled = false) != null }
                        if (!target) issues += Issue(at, "注入槽不存在或不是目标模型节点上的注入槽")
                    }
                }
                if (node.actionId in setOf("history.view.copy", "history.memory.write", "history.memory.select")) {
                    issues += ActionConfigurationRules.historyArguments(node, nodePath)
                }
                if (node.actionId == "text.extract") {
                    issues += ActionConfigurationRules.textExtraction(node, nodePath)
                }
            }
            val visiting = mutableSetOf<String>()
            val visited = mutableSetOf<String>()
            fun visit(id: String) {
                if (id in visited) return
                if (!visiting.add(id)) { issues += Issue(path, "工作流有循环依赖"); return }
                workflow.nodes.firstOrNull { it.id == id }?.dependsOn?.filter { it in ids }?.forEach(::visit)
                visiting.remove(id)
                visited.add(id)
            }
            workflow.nodes.forEach { visit(it.id) }
        }
        return issues.distinct()
    }

    private fun checkMetadata(library: Library, issues: MutableList<Issue>) {
        val tagIds = library.promptTags.map { it.id }
        if (tagIds.any { it.isBlank() } || tagIds.distinct().size != tagIds.size)
            issues += Issue("library.promptTagIds", "标签 ID 不能为空或重复")
        val catalogue = tagIds.toSet()
        fun tags(ids: List<String>, path: String, available: Set<String> = catalogue) {
            if (ids.distinct().size != ids.size) issues += Issue("$path.tagIds", "不能重复分配同一标签")
            ids.filter { it.isBlank() || it !in available }.distinct().forEach {
                issues += Issue("$path.tagIds", "标签不存在：$it")
            }
        }
        fun slugs(values: List<Pair<String, String>>, path: String) {
            // Empty defaults remain legal for older direct in-memory constructors.
            val supplied = values.filter { it.second.isNotEmpty() }
            supplied.forEach { (itemPath, slug) ->
                MacroNaming.slugProblem(slug)?.let { issues += Issue("$itemPath.slug", it) }
            }
            supplied.groupingBy { it.second }.eachCount().filterValues { it > 1 }.keys.forEach {
                issues += Issue("$path.slugs", "同级英文标识重复：$it")
            }
        }
        slugs(library.promptTags.map { "tag.${it.id}" to it.slug }, "library.promptTags")
        library.promptTags.forEach { tag ->
            MacroNaming.nameProblem(tag.name)?.let { issues += Issue("tag.${tag.id}.name", it) }
        }
        slugs(library.characters.map { "character.${it.id}" to it.slug }, "library.characters")
        slugs(library.packages.map { "pack.${it.id}" to it.slug }, "library.packages")
        slugs(library.mainCalls.map { "main.${it.id}" to it.slug }, "library.mainCalls")
        slugs(library.groups.map { "group.${it.id}" to it.slug }, "library.groups")
        slugs(library.personas.map { "persona.${it.id}" to it.slug }, "library.personas")
        library.personas.forEach { tags(it.tagIds, "persona.${it.id}") }
        fun entries(entries: List<PromptEntry>, path: String) {
            slugs(entries.map { "entry.${it.id}" to it.slug }, "$path.entries")
            entries.forEach { tags(it.tagIds, "entry.${it.id}") }
        }
        library.characters.forEach { entries(it.entries, "character.${it.id}") }
        library.packages.forEach { entries(it.entries, "pack.${it.id}") }
        fun workflows(workflows: List<Workflow>, path: String) {
            slugs(workflows.map { "workflow.${it.id}" to it.slug }, "$path.workflows")
            workflows.forEach { workflow ->
                val workflowPath = "workflow.${workflow.id}"
                val workflowTags = ExecutionResources.forWorkflow(library, workflow.id).promptTags.mapTo(mutableSetOf()) { it.id }
                slugs(workflow.nodes.map { "$workflowPath.node.${it.id}" to it.slug }, "$workflowPath.nodes")
                workflow.nodes.forEach { node ->
                    val nodePath = "$workflowPath.node.${node.id}"
                    tags(node.tagIds, nodePath, workflowTags)
                    slugs(node.blocks.map { "$nodePath.block.${it.id}" to it.slug }, "$nodePath.blocks")
                    node.blocks.forEach { tags(it.tagIds, "$nodePath.block.${it.id}", workflowTags) }
                }
            }
        }
        library.mainCalls.forEach { workflows(it.configuredWorkflows(), "main.${it.id}") }
        library.groups.forEach { workflows(it.workflows, "group.${it.id}") }
    }

    /** Only saved structure is checked here; unavailable old sources are diagnosed when read. */
    private fun checkMemory(memory: HistoryMemoryState, path: String, issues: MutableList<Issue>) {
        try {
            HistoryMemory.validateSegments(memory.segments)
        } catch (failure: IllegalArgumentException) {
            issues += Issue("$path.segments", failure.message ?: "记忆段关系无效")
        } catch (failure: IllegalStateException) {
            issues += Issue("$path.segments", failure.message ?: "记忆段关系无效")
        }
        memory.segments.forEach { (id, segment) ->
            if (id != segment.id || segment.summary.isBlank() || segment.sources.isEmpty() ||
                segment.sources.map { it.messageId }.distinct().size != segment.sources.size ||
                segment.sources.map { it.sourceKind }.distinct().size > 1)
                issues += Issue("$path.segments.$id", "记忆段身份、摘要或来源无效")
        }
        try {
            HistoryMemory.validateCaptures(memory)
        } catch (failure: IllegalArgumentException) {
            issues += Issue("$path.captures", failure.message ?: "历史捕获来源无效")
        } catch (failure: IllegalStateException) {
            issues += Issue("$path.captures", failure.message ?: "历史捕获来源无效")
        }
        memory.views.forEach { (id, versions) ->
            if (!MacroNaming.isValidSlug(id) || versions.isEmpty()) issues += Issue("$path.views.$id", "历史副本标识或版本无效")
            versions.forEachIndexed { index, view ->
                if (view.viewId != id || view.revision != index + 1 || view.name.isBlank() || view.projectionVersion !in 1..2 || view.captureId !in memory.captures ||
                    (view.baseViewId == null) != (view.baseRevision == null) || view.segmentModes.keys.any { id ->
                        memory.segments[id]?.sources?.all { it.sourceKind == view.sourceKind } != true
                    } ||
                    memory.captures[view.captureId].orEmpty().any { it.sourceKind != view.sourceKind })
                    issues += Issue("$path.views.$id.${view.revision}", "历史副本结构无效")
                if (view.baseViewId != null && memory.views[view.baseViewId].orEmpty().none {
                    it.revision == view.baseRevision && it.sourceKind == view.sourceKind &&
                        it.projectionVersion == view.projectionVersion &&
                        (it.captureId == view.captureId || view.projectionVersion >= 2 &&
                            runCatching { HistoryMemory.captureExtends(memory, it.captureId, view.captureId) }.getOrDefault(false))
                })
                    issues += Issue("$path.views.$id.${view.revision}", "历史副本基础版本不存在")
            }
        }
    }

    private fun validHttpsAddress(value: String): Boolean = try {
        val uri = java.net.URI(value)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.query == null && uri.fragment == null
    } catch (_: Exception) { false }

    private fun checkHistory(main: MainCall, issues: MutableList<Issue>, executionVersion: Int) {
        val history = main.history
        val path = "main.${main.id}.history"
        if (history.mergeMessages && history.apiRole == null)
            issues += Issue("$path.mergeMessages", "合并历史时请选择统一的 API 角色")
        fun point(point: HistoryPoint, endpoint: Boolean, at: String) {
            val number = point.value
            val valid = number.isFinite() && when (point.anchor) {
                HistoryAnchor.START, HistoryAnchor.END -> number == number.toInt().toDouble() &&
                    number >= if (endpoint) 1.0 else 0.0
                HistoryAnchor.PERCENT -> number in 0.0..100.0
            }
            if (!valid) issues += Issue(at, if (endpoint) "历史范围须用正整数楼号或 0–100%" else "历史插入位置须用非负整数间隙或 0–100%")
        }
        point(history.range.from, true, "$path.range.from")
        point(history.range.through, true, "$path.range.through")
        val from = history.range.from
        val through = history.range.through
        if (from.anchor == through.anchor && when (from.anchor) {
                HistoryAnchor.START, HistoryAnchor.PERCENT -> from.value > through.value
                HistoryAnchor.END -> from.value < through.value
            }) issues += Issue("$path.range", "历史范围起点不能晚于终点")
        val rules = history.speakers.map { it.speaker }
        if (rules.distinct().size != rules.size) issues += Issue("$path.speakers", "发言人规则重复")
        val ids = history.floors.map { it.messageId }
        if (ids.any { it.isBlank() } || ids.distinct().size != ids.size) issues += Issue("$path.floors", "历史消息规则 ID 为空或重复")
        fun wrapper(text: String, at: String) {
            val token = Regex("\\{\\{([^{}]*)\\}\\}")
            if (token.replace(text, "").let { it.contains("{{") || it.contains("}}") } ||
                token.findAll(text).any { it.groupValues[1].trim() !in setOf("speaker", "speaker.name", "floor") })
                issues += Issue(at, "历史包装只支持 speaker、speaker.name、floor")
        }
        wrapper(history.floorPrefix, "$path.floorPrefix")
        wrapper(history.floorSuffix, "$path.floorSuffix")
        history.speakers.forEachIndexed { index, rule ->
            rule.prefix?.let { wrapper(it, "$path.speakers.$index.prefix") }
            rule.suffix?.let { wrapper(it, "$path.speakers.$index.suffix") }
        }
        history.floors.forEachIndexed { index, rule ->
            rule.prefix?.let { wrapper(it, "$path.floors.$index.prefix") }
            rule.suffix?.let { wrapper(it, "$path.floors.$index.suffix") }
        }
        val insertionIds = history.insertions.map { it.id }
        if (insertionIds.any { it.isBlank() } || insertionIds.distinct().size != insertionIds.size)
            issues += Issue("$path.insertions", "历史插入 ID 为空或重复")
        history.insertions.forEachIndexed { index, insertion ->
            point(insertion.at, false, "$path.insertions.$index.at")
            checkTemplate(insertion.template, "$path.insertions.$index.template", issues, executionVersion)
        }
        val processor = history.processor
        if (processor.minimumNewFloors < 1 || processor.keepRecentFloors < 0 || processor.protectFirstFloors < 0 ||
            processor.maximumBatchFloors?.let { it < 1 } == true)
            issues += Issue("$path.processor", "历史门槛与批次上限须大于零，保留楼数不能为负")
        processor.aggregationWorkflow?.let { aggregation ->
            if (aggregation.afterWorkflowId != processor.workflow.id)
                issues += Issue("$path.processor.aggregationWorkflow", "大摘要须由对应小摘要调用完成触发")
        }
        if (processor.workflow.enabled && processor.workflow.nodes.none { it.enabled && it.outputKey == processor.outputKey })
            issues += Issue("$path.processor.outputKey", "历史处理输出键必须来自已启用节点")
        if (processor.workflow.enabled) processor.workflow.nodes.filter { it.enabled }.forEach { node ->
            if (node.includeHistory && "{{history.pending}}" in node.inputTemplate)
                issues += Issue("$path.processor.node.${node.id}.input", "自动历史与 pending 输入会重复；请选择一种")
        }
    }

    private fun checkCondition(condition: Condition?, path: String, issues: MutableList<Issue>) {
        val expression = condition?.expression?.takeIf { it.isNotBlank() } ?: return
        ConditionExpressions.validate(expression)?.let { issues += Issue("$path.expression", it) }
    }

    private fun checkTemplate(template: String, path: String, issues: MutableList<Issue>, executionVersion: Int) {
        val scan = MacroTemplateTokens.scan(template, executionVersion)
        scan.diagnostics.forEach { issues += Issue(path, it.message) }
        scan.tokens.filter { it.closed }.forEach { token ->
            macroExpressionError(token.expression.trim(), executionVersion)?.let { issues += Issue(path, it) }
        }
    }

    private fun checkRecognition(recognition: Recognition?, path: String, issues: MutableList<Issue>, executionVersion: Int) {
        issues += RecognitionEvaluator.validate(recognition, path, executionVersion)
    }
}
