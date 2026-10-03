package dev.gray.companion.core

object LibraryValidator {
    fun validate(library: Library): List<Issue> {
        val issues = mutableListOf<Issue>()
        if (library.schemaVersion != 1) issues += Issue("library.schemaVersion", "不支持的资源版本")
        fun unique(values: List<String>, path: String) {
            values.filter { it.isBlank() }.forEach { issues += Issue(path, "ID 或名称不能为空") }
            values.filter { it.isNotBlank() }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.forEach {
                issues += Issue(path, "重复值：$it")
            }
        }
        unique(library.characters.map { it.id } + library.packages.map { it.id } + library.mainCalls.map { it.id } + library.groups.map { it.id } + library.profiles.map { it.id } + library.connections.map { it.id } + library.sessions.map { it.id } + library.personas.map { it.id }, "library.ids")
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
            checkTemplate(persona.prompt, "$path.prompt", issues)
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
            if (entry.isTemplate) checkTemplate(entry.text, "entry.${entry.id}.text", issues)
        }
        library.characters.forEach { card ->
            if (card.avatar != null && card.avatar.decodedBytes() == null) issues += Issue("character.${card.id}.avatar", "头像必须是 32 KB 以内的 JPEG 或 PNG 图片")
            unique(card.entries.map { it.key }, "character.${card.id}.entries")
            if (card.entries.any { it.key == "name" }) issues += Issue("character.${card.id}.entries", "name 是角色名称保留键")
            checkTemplate(card.greeting, "character.${card.id}.greeting", issues)
            card.boundPackageIds.forEach { if (library.packages.none { pack -> pack.id == it }) issues += Issue("character.${card.id}", "绑定的提示词包不存在") }
        }
        library.packages.forEach { unique(it.entries.map { entry -> entry.key }, "pack.${it.id}.entries") }
        library.groups.forEach { group ->
            group.boundPackageIds.forEach { if (library.packages.none { pack -> pack.id == it }) issues += Issue("group.${group.id}", "绑定的提示词包不存在") }
        }
        fun checkSession(session: Session, path: String) {
            if (session.personaId != null && library.personas.none { it.id == session.personaId })
                issues += Issue("$path.personaId", "会话用户身份不存在")
            if (session.characterId.isNotBlank() && library.characters.none { it.id == session.characterId }) issues += Issue(path, "角色卡不存在")
            if (session.mainCallId.isNotBlank() && library.mainCalls.none { it.id == session.mainCallId }) issues += Issue(path, "主调用不存在")
            session.packageIds.forEach { id -> if (library.packages.none { it.id == id }) issues += Issue(path, "提示词包不存在") }
            session.groupIds.forEach { id -> if (library.groups.none { it.id == id }) issues += Issue(path, "调用组不存在") }
            session.historyDigests.forEach { (mainId, digest) ->
                if (library.mainCalls.none { it.id == mainId }) issues += Issue("$path.historyDigests.$mainId", "历史摘要对应的主调用不存在")
                val body = session.messages.filter { it.outputSlot == OutputSlot.BODY && !it.deleted }
                if (digest.coveredFloors !in 1..body.size || body[digest.coveredFloors - 1].id != digest.throughMessageId)
                    issues += Issue("$path.historyDigests.$mainId", "历史摘要覆盖范围与原消息不一致")
            }
        }
        library.sessions.forEach { checkSession(it, "session.${it.id}") }
        library.savedChats.forEach { checkSession(it.snapshot, "savedChat.${it.id}.snapshot") }
        (library.sessions.map { "session.${it.id}" to it } + library.savedChats.map { "savedChat.${it.id}.snapshot" to it.snapshot })
            .forEach { (path, session) ->
                val main = library.mainCalls.firstOrNull { it.id == session.mainCallId } ?: return@forEach
                val floors = session.messages.count { it.outputSlot == OutputSlot.BODY && !it.deleted }
                if (!HistoryContext.rangeValid(main.history.range, floors))
                    issues += Issue("$path.history", "所选历史范围起点晚于终点")
            }
        if (library.selectedProfileId != null && library.profiles.none { it.id == library.selectedProfileId }) issues += Issue("library.selectedProfileId", "所选模型不存在")
        if (library.titleConfig.modelProfileId != null && library.profiles.none { it.id == library.titleConfig.modelProfileId })
            issues += Issue("library.titleConfig.modelProfileId", "标题模型不存在")
        if (library.titleConfig.enabled && library.titleConfig.prompt.isBlank())
            issues += Issue("library.titleConfig.prompt", "标题提示词不能为空")
        if (library.selectedSessionId != null && library.sessions.none { it.id == library.selectedSessionId }) issues += Issue("library.selectedSessionId", "所选会话不存在")
        library.mainCalls.forEach { main ->
            main.boundPackageIds.forEach { id ->
                if (library.packages.none { it.id == id }) issues += Issue("main.${main.id}.boundPackageIds", "绑定的提示词包不存在")
            }
            if (main.primaryWorkflowId != null && main.workflows.none { it.id == main.primaryWorkflowId })
                issues += Issue("main.${main.id}.primaryWorkflowId", "默认调用不存在")
            main.boundGroupIds.forEach { id -> if (library.groups.none { it.id == id }) issues += Issue("main.${main.id}.boundGroupIds", "绑定的调用组不存在") }
            checkHistory(main, issues)
        }
        val tiles = library.mainCalls.flatMap { it.tiles } + library.groups.flatMap { it.tiles }
        unique(tiles.map { it.id }, "library.tileIds")
        tiles.forEach { tile ->
            val path = "tile.${tile.id}"
            checkTemplate(tile.template, "$path.template", issues)
            SafePatterns.validate(tile.pattern, tile.captureGroup)?.let { issues += Issue("$path.pattern", it) }
            if (tile.title.isBlank()) issues += Issue(path, "磁贴名称不能为空")
            if (!tile.minimum.isFinite() || !tile.maximum.isFinite() || (tile.kind == MacroTileKind.PROGRESS && tile.maximum <= tile.minimum))
                issues += Issue(path, "进度上下限必须是有效数字，且上限大于下限")
            if (tile.kind == MacroTileKind.STATUS && tile.fields.isEmpty()) issues += Issue("$path.fields", "状态磁贴至少需要一个字段")
            tile.fields.forEachIndexed { index, field ->
                val at = "$path.fields.$index"
                if (!StatusFields.validKey(field.key)) issues += Issue("$at.key", "状态字段 XML 路径无效")
                if (field.title.isBlank()) issues += Issue("$at.title", "状态字段名称不能为空")
                if (field.kind == MacroTileKind.STATUS) issues += Issue("$at.kind", "状态字段不能嵌套状态视图")
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
        val processorIds = library.mainCalls.map { it.history.processor.workflow.id }.toSet()
        val allWorkflows = library.mainCalls.flatMap { it.workflows + it.history.processor.workflow } + library.groups.flatMap { it.workflows }
        unique(allWorkflows.map { it.id }, "library.workflowIds")
        unique(allWorkflows.flatMap { it.nodes }.flatMap { it.blocks }.map { it.id }, "library.blockIds")
        allWorkflows.forEach { workflow ->
            val path = "workflow.${workflow.id}"
            if (workflow.title.isBlank()) issues += Issue(path, "工作流名称不能为空")
            if (workflow.trigger.isBlank()) issues += Issue(path, "触发事件不能为空")
            if (workflow.id in processorIds && workflow.trigger != "history.process") issues += Issue(path, "历史处理调用须使用 history.process")
            if (workflow.id !in processorIds && workflow.trigger == "history.process") issues += Issue(path, "history.process 只供历史处理调用使用")
            if (workflow.modelProfileId != null && library.profiles.none { it.id == workflow.modelProfileId })
                issues += Issue("$path.modelProfileId", "工作流模型不存在")
            checkCondition(workflow.condition, "$path.condition", issues)
            checkRecognition(workflow.recognition, "$path.recognition", issues)
            unique(workflow.nodes.map { it.id }, "$path.nodes")
            val ids = workflow.nodes.map { it.id }.toSet()
            val writerCategories = mutableMapOf<String, String>()
            workflow.nodes.forEach { node ->
                val nodePath = "$path.node.${node.id}"
                if (node.title.isBlank()) issues += Issue(nodePath, "节点名称不能为空")
                if (node.actionId.isBlank()) issues += Issue(nodePath, "动作不能为空")
                if (node.outputKey.isBlank()) issues += Issue(nodePath, "输出键不能为空")
                if (node.generation.profileId != null && library.profiles.none { it.id == node.generation.profileId })
                    issues += Issue("$nodePath.generation.profileId", "节点模型不存在")
                node.generation.temperature?.let { if (!it.isFinite()) issues += Issue("$nodePath.generation.temperature", "温度必须是有限数字") }
                node.generation.topP?.let { if (!it.isFinite() || it !in 0f..1f) issues += Issue("$nodePath.generation.topP", "Top P 必须在 0 到 1 之间") }
                node.generation.maxTokens?.let { if (it <= 0) issues += Issue("$nodePath.generation.maxTokens", "最大令牌数必须大于零") }
                issues += TextProcessing.validate(node.inputProcessing, "$nodePath.inputProcessing")
                issues += TextProcessing.validate(node.outputProcessing, "$nodePath.outputProcessing")
                if (node.outputProcessing.isNotEmpty() && node.actionId in setOf("state.set", "state.increment", "macro.write", "macro.activate", "macro.process"))
                    issues += Issue("$nodePath.outputProcessing", "此动作的输出处理会与目标值不一致；请在宏处理步骤中修改值")
                if (node.actionId == "macro.process") {
                    val config = node.macroProcess
                    val target = config.key.ifBlank { node.arguments["key"].orEmpty() }
                    if (canonicalWriteAddress(target) == null) issues += Issue("$nodePath.macroProcess.key", "宏目标地址无效")
                    if (config.mode == MacroProcessMode.COPY) {
                        val source = config.source.trim().removeSurrounding("{{", "}}").trim()
                        if (canonicalWriteAddress(source.substringBefore('@')) == null ||
                            !validVersionSelector(source.substringAfter('@', "latest")))
                            issues += Issue("$nodePath.macroProcess.source", "源宏地址或版本选择无效")
                    }
                    checkTemplate(config.initial, "$nodePath.macroProcess.initial", issues)
                    issues += TextProcessing.validate(config.steps, "$nodePath.macroProcess.steps")
                }
                checkCondition(node.condition, "$nodePath.condition", issues)
                checkRecognition(node.recognition, "$nodePath.recognition", issues)
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
                checkTemplate(node.inputTemplate, "$nodePath.input", issues)
                node.blocks.forEach { block ->
                    val blockPath = "$nodePath.block.${block.id}"
                    checkTemplate(block.template, blockPath, issues)
                    issues += TextProcessing.validate(block.processing, "$blockPath.processing")
                    if (containsStructuredHistoryToken(block.template) && !isStructuredHistoryBlock(block.template))
                        issues += Issue(blockPath, "结构化 history 须单独占一个提示块；混合文本请使用 history.text")
                    checkCondition(block.condition, "$blockPath.condition", issues)
                    checkRecognition(block.recognition, "$blockPath.recognition", issues)
                    if (block.depth < 0) issues += Issue("$blockPath.depth", "插入深度不能小于零")
                }
                node.arguments.forEach { (key, value) -> checkTemplate(value, "$nodePath.argument.$key", issues) }
                if (node.actionId == "text.extract") {
                    SafePatterns.validate(node.arguments["pattern"].orEmpty(), node.arguments["captureGroup"]?.toIntOrNull() ?: 0)?.let {
                        issues += Issue("$nodePath.pattern", it)
                    }
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

    private fun validHttpsAddress(value: String): Boolean = try {
        val uri = java.net.URI(value)
        uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank() &&
            uri.userInfo == null && uri.query == null && uri.fragment == null
    } catch (_: Exception) { false }

    private fun checkHistory(main: MainCall, issues: MutableList<Issue>) {
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
            checkTemplate(insertion.template, "$path.insertions.$index.template", issues)
        }
        val processor = history.processor
        if (processor.minimumNewFloors < 1 || processor.keepRecentFloors < 0)
            issues += Issue("$path.processor", "历史处理门槛须大于零，保留楼数不能为负")
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

    private fun checkTemplate(template: String, path: String, issues: MutableList<Issue>) {
        val token = Regex("\\{\\{([^{}]*)\\}\\}")
        if (token.replace(template, "").let { it.contains("{{") || it.contains("}}") }) issues += Issue(path, "宏格式不完整")
        token.findAll(template).forEach { match ->
            val expression = match.groupValues[1].trim()
            macroExpressionError(expression)?.let { issues += Issue(path, it) }
        }
    }

    private fun checkRecognition(recognition: Recognition?, path: String, issues: MutableList<Issue>) {
        issues += RecognitionEvaluator.validate(recognition, path)
    }
}
