package dev.gray.companion.core

/** Public fields are explicit, stable paths into the original resources. No extension stores a value. */
object ExtensionFieldRegistry {
    private val actions = ActionRegistry.defaults().descriptors().associateBy { it.id }

    fun list(library: Library, resource: ExtensionResourceRef): List<ExtensionFieldDescriptor> = buildList {
        fun addField(field: ExtensionField, title: String, type: ActionFieldType = ActionFieldType.STRING,
                     entryId: String? = null, workflowId: String? = null, nodeId: String? = null, blockId: String? = null,
                     nullable: Boolean = false, integer: Boolean = false, min: Double? = null, max: Double? = null,
                     choices: List<ActionChoice> = emptyList(), argument: ActionFieldDescriptor? = null) {
            add(ExtensionFieldDescriptor(ExtensionFieldRef(resource.kind, resource.id, field, entryId, workflowId,
                nodeId, blockId, argument?.key), title, argument?.type ?: type, argument?.required ?: false,
                nullable, argument?.integer ?: integer, min, max, argument?.choices ?: choices))
        }
        val entries = when (resource.kind) {
            ExtensionResourceKind.CHARACTER -> library.characters.firstOrNull { it.id == resource.id }?.let {
                addField(ExtensionField.CHARACTER_DESCRIPTION, "角色描述"); it.entries
            }
            ExtensionResourceKind.PACKAGE -> library.packages.firstOrNull { it.id == resource.id }?.entries
            else -> null
        }.orEmpty()
        entries.forEach { entry ->
            addField(ExtensionField.ENTRY_TEXT, "${entry.title.ifBlank { entry.key }} · 正文", ActionFieldType.TEMPLATE, entryId = entry.id)
            addField(ExtensionField.ENTRY_ENABLED, "${entry.title.ifBlank { entry.key }} · 启用", ActionFieldType.BOOLEAN, entryId = entry.id)
        }
        val workflows = when (resource.kind) {
            ExtensionResourceKind.MAIN -> library.mainCalls.firstOrNull { it.id == resource.id }?.workflows
            ExtensionResourceKind.GROUP -> library.groups.firstOrNull { it.id == resource.id }?.workflows
            else -> null
        }.orEmpty()
        val profiles = library.profiles.map { ActionChoice(it.id, it.name) }
        workflows.forEach { workflow ->
            fun wf(field: ExtensionField, title: String, type: ActionFieldType = ActionFieldType.STRING,
                   nullable: Boolean = false, integer: Boolean = false, choices: List<ActionChoice> = emptyList()) =
                addField(field, "${workflow.title} · $title", type, workflowId = workflow.id,
                    nullable = nullable, integer = integer, choices = choices)
            wf(ExtensionField.WORKFLOW_MODEL, "模型", nullable = true, choices = profiles)
            wf(ExtensionField.WORKFLOW_ENABLED, "启用", ActionFieldType.BOOLEAN)
            wf(ExtensionField.WORKFLOW_QUEUED, "排队", ActionFieldType.BOOLEAN)
            wf(ExtensionField.WORKFLOW_PRIORITY, "优先级", ActionFieldType.NUMBER, integer = true)
            workflow.nodes.forEach { node ->
                fun nf(field: ExtensionField, title: String, type: ActionFieldType = ActionFieldType.STRING,
                       nullable: Boolean = false, integer: Boolean = false, min: Double? = null, max: Double? = null,
                       choices: List<ActionChoice> = emptyList(), argument: ActionFieldDescriptor? = null) =
                    addField(field, "${workflow.title} / ${node.title} · $title", type, workflowId = workflow.id,
                        nodeId = node.id, nullable = nullable, integer = integer, min = min, max = max, choices = choices, argument = argument)
                nf(ExtensionField.NODE_ENABLED, "启用", ActionFieldType.BOOLEAN)
                if (actions[node.actionId]?.usesModel == true) {
                    nf(ExtensionField.NODE_MODEL, "模型", nullable = true, choices = profiles)
                    nf(ExtensionField.NODE_TEMPERATURE, "温度", ActionFieldType.NUMBER, nullable = true)
                    nf(ExtensionField.NODE_TOP_P, "Top P", ActionFieldType.NUMBER, nullable = true, min = 0.0, max = 1.0)
                    nf(ExtensionField.NODE_MAX_TOKENS, "最大令牌数", ActionFieldType.NUMBER, nullable = true, integer = true, min = 1.0)
                    nf(ExtensionField.NODE_REASONING, "推理强度", nullable = true, choices = ModelReasoning.entries.map { ActionChoice(it.name, it.name) })
                }
                actions[node.actionId]?.fields.orEmpty().forEach { nf(ExtensionField.NODE_ARGUMENT, it.title, nullable = !it.required, argument = it) }
                node.blocks.forEach { block ->
                    fun bf(field: ExtensionField, title: String, type: ActionFieldType, choices: List<ActionChoice> = emptyList()) =
                        addField(field, "${workflow.title} / ${node.title} / ${block.title} · $title", type,
                            workflowId = workflow.id, nodeId = node.id, blockId = block.id, choices = choices)
                    bf(ExtensionField.BLOCK_TEMPLATE, "正文", ActionFieldType.TEMPLATE)
                    bf(ExtensionField.BLOCK_ROLE, "角色", ActionFieldType.STRING, Role.entries.map { ActionChoice(it.name, it.name) })
                    bf(ExtensionField.BLOCK_ENABLED, "启用", ActionFieldType.BOOLEAN)
                }
            }
        }
    }

    fun describe(library: Library, target: ExtensionFieldRef): ExtensionFieldDescriptor? =
        list(library, ExtensionResourceRef(target.kind, target.resourceId)).firstOrNull { it.target == target }

    fun read(library: Library, target: ExtensionFieldRef): String? {
        if (describe(library, target) == null) return null
        val card = library.characters.firstOrNull { it.id == target.resourceId && target.kind == ExtensionResourceKind.CHARACTER }
        val entries = card?.entries ?: library.packages.firstOrNull { it.id == target.resourceId && target.kind == ExtensionResourceKind.PACKAGE }?.entries
        val entry = entries?.firstOrNull { it.id == target.entryId }
        val workflows = when (target.kind) {
            ExtensionResourceKind.MAIN -> library.mainCalls.firstOrNull { it.id == target.resourceId }?.workflows
            ExtensionResourceKind.GROUP -> library.groups.firstOrNull { it.id == target.resourceId }?.workflows
            else -> null
        }
        val workflow = workflows?.firstOrNull { it.id == target.workflowId }
        val node = workflow?.nodes?.firstOrNull { it.id == target.nodeId }
        val block = node?.blocks?.firstOrNull { it.id == target.blockId }
        return when (target.field) {
            ExtensionField.CHARACTER_DESCRIPTION -> card?.description
            ExtensionField.ENTRY_TEXT -> entry?.text
            ExtensionField.ENTRY_ENABLED -> entry?.enabled?.toString()
            ExtensionField.BLOCK_TEMPLATE -> block?.template
            ExtensionField.BLOCK_ROLE -> block?.role?.name
            ExtensionField.BLOCK_ENABLED -> block?.enabled?.toString()
            ExtensionField.WORKFLOW_MODEL -> workflow?.modelProfileId
            ExtensionField.WORKFLOW_ENABLED -> workflow?.enabled?.toString()
            ExtensionField.WORKFLOW_QUEUED -> workflow?.queued?.toString()
            ExtensionField.WORKFLOW_PRIORITY -> workflow?.priority?.toString()
            ExtensionField.NODE_ENABLED -> node?.enabled?.toString()
            ExtensionField.NODE_MODEL -> node?.generation?.profileId
            ExtensionField.NODE_TEMPERATURE -> node?.generation?.temperature?.toString()
            ExtensionField.NODE_TOP_P -> node?.generation?.topP?.toString()
            ExtensionField.NODE_MAX_TOKENS -> node?.generation?.maxTokens?.toString()
            ExtensionField.NODE_REASONING -> node?.generation?.reasoning?.name
            ExtensionField.NODE_ARGUMENT -> node?.arguments?.get(target.argDescriptorKey)
        }
    }

    fun write(library: Library, target: ExtensionFieldRef, value: String?, expectedValue: String?): ExtensionEditResult {
        val descriptor = describe(library, target) ?: return failure(target, "公开字段目标不存在或动作描述符已改变")
        if (read(library, target) != expectedValue) return failure(target, "字段已被其他编辑修改，请重新读取后再保存")
        valueIssue(descriptor, value)?.let { return failure(target, it) }
        fun entry(old: PromptEntry): PromptEntry = if (old.id != target.entryId) old else when (target.field) {
            ExtensionField.ENTRY_TEXT -> old.copy(text = value!!)
            ExtensionField.ENTRY_ENABLED -> old.copy(enabled = value.toBoolean())
            else -> old
        }
        fun block(old: PromptBlock): PromptBlock = if (old.id != target.blockId) old else when (target.field) {
            ExtensionField.BLOCK_TEMPLATE -> old.copy(template = value!!)
            ExtensionField.BLOCK_ROLE -> old.copy(role = Role.valueOf(value!!))
            ExtensionField.BLOCK_ENABLED -> old.copy(enabled = value.toBoolean())
            else -> old
        }
        fun node(old: WorkflowNode): WorkflowNode = if (old.id != target.nodeId) old else when (target.field) {
            ExtensionField.NODE_ENABLED -> old.copy(enabled = value.toBoolean())
            ExtensionField.NODE_MODEL -> old.copy(generation = old.generation.copy(profileId = value))
            ExtensionField.NODE_TEMPERATURE -> old.copy(generation = old.generation.copy(temperature = value?.toFloat()))
            ExtensionField.NODE_TOP_P -> old.copy(generation = old.generation.copy(topP = value?.toFloat()))
            ExtensionField.NODE_MAX_TOKENS -> old.copy(generation = old.generation.copy(maxTokens = value?.toInt()))
            ExtensionField.NODE_REASONING -> old.copy(generation = old.generation.copy(reasoning = value?.let(ModelReasoning::valueOf)))
            ExtensionField.NODE_ARGUMENT -> old.copy(arguments = if (value == null) old.arguments - target.argDescriptorKey!! else old.arguments + (target.argDescriptorKey!! to value))
            else -> old.copy(blocks = old.blocks.map(::block))
        }
        fun workflow(old: Workflow): Workflow = if (old.id != target.workflowId) old else when (target.field) {
            ExtensionField.WORKFLOW_MODEL -> old.copy(modelProfileId = value)
            ExtensionField.WORKFLOW_ENABLED -> old.copy(enabled = value.toBoolean())
            ExtensionField.WORKFLOW_QUEUED -> old.copy(queued = value.toBoolean())
            ExtensionField.WORKFLOW_PRIORITY -> old.copy(priority = value!!.toInt())
            else -> old.copy(nodes = old.nodes.map(::node))
        }
        val candidate = when (target.kind) {
            ExtensionResourceKind.CHARACTER -> library.copy(characters = library.characters.map { if (it.id != target.resourceId) it else
                if (target.field == ExtensionField.CHARACTER_DESCRIPTION) it.copy(description = value!!) else it.copy(entries = it.entries.map(::entry)) })
            ExtensionResourceKind.PACKAGE -> library.copy(packages = library.packages.map { if (it.id != target.resourceId) it else it.copy(entries = it.entries.map(::entry)) })
            ExtensionResourceKind.MAIN -> library.copy(mainCalls = library.mainCalls.map { if (it.id != target.resourceId) it else it.copy(workflows = it.workflows.map(::workflow)) })
            ExtensionResourceKind.GROUP -> library.copy(groups = library.groups.map { if (it.id != target.resourceId) it else it.copy(workflows = it.workflows.map(::workflow)) })
        }
        val issues = LibraryValidator.validate(candidate).filter { it.error }
        return ExtensionEditResult(candidate.takeIf { issues.isEmpty() }, issues, listOf("更新实际字段：${descriptor.title}"))
    }

    private fun valueIssue(field: ExtensionFieldDescriptor, value: String?): String? {
        if (value == null) return if (field.nullable) null else "此字段不允许继承或空值"
        if (field.required && value.isBlank()) return "必填字段不能为空"
        if ((field.choices.isNotEmpty() || field.target.field in setOf(ExtensionField.WORKFLOW_MODEL, ExtensionField.NODE_MODEL)) && field.choices.none { it.value == value }) return "请选择现有的字段选项"
        return when (field.type) {
            ActionFieldType.BOOLEAN -> if (value !in setOf("true", "false")) "字段必须为 true 或 false" else null
            ActionFieldType.NUMBER -> {
                val number = value.toDoubleOrNull()
                when {
                    number == null || !number.isFinite() || !value.toFloat().isFinite() -> "字段必须为有限数字"
                    field.integer && value.toIntOrNull() == null -> "字段必须为范围内的整数"
                    field.min != null && number < field.min -> "字段不能小于 ${field.min}"
                    field.max != null && number > field.max -> "字段不能大于 ${field.max}"
                    else -> null
                }
            }
            ActionFieldType.MACRO -> if (MacroReferences.canonicalAddress(value) == null && value.isNotBlank()) "宏地址无效" else null
            else -> null
        }
    }
    private fun failure(target: ExtensionFieldRef, message: String) = ExtensionEditResult(null,
        listOf(Issue("extension.field.${target.resourceId}.${target.field}", message)))
}
