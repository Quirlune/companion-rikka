package dev.gray.companion.core

/** Public metadata, reads and edits are registered together over the original typed resources. */
object ExtensionFieldRegistry {
    private val actions = ActionRegistry.defaults().descriptors().associateBy { it.id }
    private data class Binding(
        val descriptor: ExtensionFieldDescriptor,
        val read: () -> String?,
        val write: (String?) -> Library
    )

    fun list(library: Library, resource: ExtensionResourceRef): List<ExtensionFieldDescriptor> =
        bindings(library, resource).map { it.descriptor }

    private fun bindings(library: Library, resource: ExtensionResourceRef): List<Binding> = buildList {
        fun addField(field: ExtensionField, title: String, type: ActionFieldType = ActionFieldType.STRING,
                     entryId: String? = null, workflowId: String? = null, nodeId: String? = null, blockId: String? = null,
                     nullable: Boolean = false, integer: Boolean = false, min: Double? = null, max: Double? = null,
                     choices: List<ActionChoice> = emptyList(), argument: ActionFieldDescriptor? = null,
                     macroKey: String? = null, issueKey: String? = null,
                     read: () -> String?, write: (String?) -> Library) {
            val target = ExtensionFieldRef(resource.kind, resource.id, field, entryId, workflowId,
                nodeId, blockId, argument?.key ?: macroKey)
            add(Binding(ExtensionFieldDescriptor(target, title, argument?.type ?: type, argument?.required ?: false,
                nullable, argument?.integer ?: integer, min, max, argument?.choices ?: choices, issueKey), read, write))
        }
        val entries = when (resource.kind) {
            ExtensionResourceKind.CHARACTER -> library.characters.firstOrNull { it.id == resource.id }?.let { card ->
                addField(ExtensionField.CHARACTER_DESCRIPTION, "角色描述", issueKey = "description",
                    read = { card.description }, write = { value -> library.copy(characters = library.characters.map {
                        if (it.id == card.id) it.copy(description = value!!) else it
                    }) })
                card.entries
            }
            ExtensionResourceKind.PACKAGE -> library.packages.firstOrNull { it.id == resource.id }?.entries
            else -> null
        }.orEmpty()
        entries.forEach { entry ->
            val title = entry.title.ifBlank { entry.key }
            addField(ExtensionField.ENTRY_TEXT, "$title · 正文", ActionFieldType.TEMPLATE, entryId = entry.id,
                issueKey = "text", read = { entry.text },
                write = { value -> editEntry(library, resource, entry.id) { it.copy(text = value!!) } })
            val fixedEnabled = resource.kind == ExtensionResourceKind.CHARACTER && CharacterFields.isBasic(CharacterFields.canonicalKey(entry.key))
            addField(ExtensionField.ENTRY_ENABLED, "$title · ${if (fixedEnabled) "固定启用" else "启用"}",
                ActionFieldType.BOOLEAN, entryId = entry.id, issueKey = "enabled",
                choices = if (fixedEnabled) listOf(ActionChoice("true", "固定启用")) else emptyList(),
                read = { entry.enabled.toString() },
                write = { value -> editEntry(library, resource, entry.id) { it.copy(enabled = value.toBoolean()) } })
        }
        if (resource.kind == ExtensionResourceKind.GROUP) {
            library.groups.firstOrNull { it.id == resource.id }?.let { group ->
                group.stateDefaults.forEach { (key, current) ->
                    // The engine consumes the default literally, never as a template.
                    addField(ExtensionField.GROUP_STATE_DEFAULT, "宏 state.$key · 默认值", macroKey = key,
                        read = { current }, write = { value -> library.copy(groups = library.groups.map {
                            if (it.id == group.id) it.copy(stateDefaults = it.stateDefaults + (key to value!!)) else it
                        }) })
                }
            }
        }
        val workflows = when (resource.kind) {
            ExtensionResourceKind.MAIN -> library.mainCalls.firstOrNull { it.id == resource.id }?.workflows
            ExtensionResourceKind.GROUP -> library.groups.firstOrNull { it.id == resource.id }?.workflows
            else -> null
        }.orEmpty()
        val profiles = library.profiles.map { ActionChoice(it.id, it.name) }
        workflows.forEach { workflow ->
            fun wf(field: ExtensionField, title: String, issueKey: String,
                   type: ActionFieldType = ActionFieldType.STRING, nullable: Boolean = false, integer: Boolean = false,
                   choices: List<ActionChoice> = emptyList(),
                   read: () -> String?, edit: (Workflow, String?) -> Workflow) =
                addField(field, "${workflow.title} · $title", type, workflowId = workflow.id, issueKey = issueKey,
                    nullable = nullable, integer = integer, choices = choices, read = read,
                    write = { value -> editWorkflow(library, resource, workflow.id) { edit(it, value) } })
            wf(ExtensionField.WORKFLOW_MODEL, "模型", "modelProfileId", nullable = true, choices = profiles,
                read = { workflow.modelProfileId }, edit = { old, value -> old.copy(modelProfileId = value) })
            wf(ExtensionField.WORKFLOW_ENABLED, "启用", "enabled", ActionFieldType.BOOLEAN,
                read = { workflow.enabled.toString() }, edit = { old, value -> old.copy(enabled = value.toBoolean()) })
            wf(ExtensionField.WORKFLOW_QUEUED, "排队", "queued", ActionFieldType.BOOLEAN,
                read = { workflow.queued.toString() }, edit = { old, value -> old.copy(queued = value.toBoolean()) })
            wf(ExtensionField.WORKFLOW_PRIORITY, "优先级", "priority", ActionFieldType.NUMBER, integer = true,
                read = { workflow.priority.toString() }, edit = { old, value -> old.copy(priority = value!!.toInt()) })
            workflow.nodes.forEach { node ->
                fun nf(field: ExtensionField, title: String, issueKey: String? = null,
                       type: ActionFieldType = ActionFieldType.STRING, nullable: Boolean = false, integer: Boolean = false,
                       min: Double? = null, max: Double? = null, choices: List<ActionChoice> = emptyList(),
                       argument: ActionFieldDescriptor? = null,
                       read: () -> String?, edit: (WorkflowNode, String?) -> WorkflowNode) =
                    addField(field, "${workflow.title} / ${node.title} · $title", type, workflowId = workflow.id,
                        nodeId = node.id, issueKey = issueKey, nullable = nullable, integer = integer, min = min, max = max,
                        choices = choices, argument = argument, read = read,
                        write = { value -> editWorkflow(library, resource, workflow.id) { old -> old.copy(nodes = old.nodes.map {
                            if (it.id == node.id) edit(it, value) else it
                        }) } })
                nf(ExtensionField.NODE_ENABLED, "启用", "enabled", ActionFieldType.BOOLEAN,
                    read = { node.enabled.toString() }, edit = { old, value -> old.copy(enabled = value.toBoolean()) })
                if (actions[node.actionId]?.usesModel == true) {
                    nf(ExtensionField.NODE_MODEL, "模型", "profileId", nullable = true, choices = profiles,
                        read = { node.generation.profileId }, edit = { old, value -> old.copy(generation = old.generation.copy(profileId = value)) })
                    nf(ExtensionField.NODE_TEMPERATURE, "温度", "temperature", ActionFieldType.NUMBER, nullable = true,
                        read = { node.generation.temperature?.toString() }, edit = { old, value -> old.copy(generation = old.generation.copy(temperature = value?.toFloat())) })
                    nf(ExtensionField.NODE_TOP_P, "Top P", "topP", ActionFieldType.NUMBER, nullable = true, min = 0.0, max = 1.0,
                        read = { node.generation.topP?.toString() }, edit = { old, value -> old.copy(generation = old.generation.copy(topP = value?.toFloat())) })
                    nf(ExtensionField.NODE_MAX_TOKENS, "最大令牌数", "maxTokens", ActionFieldType.NUMBER, nullable = true, integer = true, min = 1.0,
                        read = { node.generation.maxTokens?.toString() }, edit = { old, value -> old.copy(generation = old.generation.copy(maxTokens = value?.toInt())) })
                    nf(ExtensionField.NODE_REASONING, "推理强度", "reasoning", nullable = true,
                        choices = ModelReasoning.entries.map { ActionChoice(it.name, it.name) },
                        read = { node.generation.reasoning?.name }, edit = { old, value -> old.copy(generation = old.generation.copy(reasoning = value?.let(ModelReasoning::valueOf))) })
                }
                actions[node.actionId]?.fields.orEmpty().forEach { argument ->
                    nf(ExtensionField.NODE_ARGUMENT, argument.title, nullable = !argument.required, argument = argument,
                        read = { node.arguments[argument.key] }, edit = { old, value -> old.copy(arguments =
                            if (value == null) old.arguments - argument.key else old.arguments + (argument.key to value)) })
                }
                node.blocks.forEach { block ->
                    fun bf(field: ExtensionField, title: String, issueKey: String, type: ActionFieldType,
                           choices: List<ActionChoice> = emptyList(),
                           read: () -> String?, edit: (PromptBlock, String?) -> PromptBlock) =
                        addField(field, "${workflow.title} / ${node.title} / ${block.title} · $title", type,
                            workflowId = workflow.id, nodeId = node.id, blockId = block.id, issueKey = issueKey,
                            choices = choices, read = read,
                            write = { value -> editWorkflow(library, resource, workflow.id) { old -> old.copy(nodes = old.nodes.map { child ->
                                if (child.id != node.id) child else child.copy(blocks = child.blocks.map {
                                    if (it.id == block.id) edit(it, value) else it
                                })
                            }) } })
                    if (!block.injectionSlot) bf(ExtensionField.BLOCK_TEMPLATE, "正文", "template", ActionFieldType.TEMPLATE,
                        read = { block.template }, edit = { old, value -> old.copy(template = value!!) })
                    bf(ExtensionField.BLOCK_ROLE, "角色", "role", ActionFieldType.STRING, Role.entries.map { ActionChoice(it.name, it.name) },
                        read = { block.role.name }, edit = { old, value -> old.copy(role = Role.valueOf(value!!)) })
                    bf(ExtensionField.BLOCK_ENABLED, "启用", "enabled", ActionFieldType.BOOLEAN,
                        read = { block.enabled.toString() }, edit = { old, value -> old.copy(enabled = value.toBoolean()) })
                }
            }
        }
    }

    private fun binding(library: Library, target: ExtensionFieldRef): Binding? =
        bindings(library, ExtensionResourceRef(target.kind, target.resourceId)).firstOrNull { it.descriptor.target == target }

    fun describe(library: Library, target: ExtensionFieldRef): ExtensionFieldDescriptor? = binding(library, target)?.descriptor

    fun read(library: Library, target: ExtensionFieldRef): String? = binding(library, target)?.read?.invoke()

    fun write(library: Library, target: ExtensionFieldRef, value: String?, expectedValue: String?): ExtensionEditResult =
        writeChecked(library, target, value, expectedValue, validateLibrary = true)

    /** Type/identity checked edit for an unfinished owning draft. Never publish without full validation. */
    fun writeDraft(library: Library, target: ExtensionFieldRef, value: String?, expectedValue: String?): ExtensionEditResult =
        writeChecked(library, target, value, expectedValue, validateLibrary = false)

    private fun writeChecked(library: Library, target: ExtensionFieldRef, value: String?, expectedValue: String?, validateLibrary: Boolean): ExtensionEditResult {
        val binding = binding(library, target) ?: return failure(target, "公开字段目标不存在或动作描述符已改变")
        if (binding.read() != expectedValue) return failure(target, "字段已被其他编辑修改，请重新读取后再保存")
        valueIssue(binding.descriptor, value)?.let { return failure(target, it) }
        val candidate = binding.write(value)
        val issues = if (validateLibrary) LibraryValidator.validate(candidate).filter { it.error } else emptyList()
        return ExtensionEditResult(candidate.takeIf { issues.isEmpty() }, issues, listOf("更新实际字段：${binding.descriptor.title}"))
    }

    private fun editEntry(library: Library, resource: ExtensionResourceRef, id: String, edit: (PromptEntry) -> PromptEntry): Library {
        fun entries(values: List<PromptEntry>) = values.map { if (it.id == id) edit(it) else it }
        return when (resource.kind) {
            ExtensionResourceKind.CHARACTER -> library.copy(characters = library.characters.map { if (it.id == resource.id) it.copy(entries = entries(it.entries)) else it })
            ExtensionResourceKind.PACKAGE -> library.copy(packages = library.packages.map { if (it.id == resource.id) it.copy(entries = entries(it.entries)) else it })
            else -> error("非词条资源")
        }
    }

    private fun editWorkflow(library: Library, resource: ExtensionResourceRef, id: String, edit: (Workflow) -> Workflow): Library {
        fun workflows(values: List<Workflow>) = values.map { if (it.id == id) edit(it) else it }
        return when (resource.kind) {
            ExtensionResourceKind.MAIN -> library.copy(mainCalls = library.mainCalls.map { if (it.id == resource.id) it.copy(workflows = workflows(it.workflows)) else it })
            ExtensionResourceKind.GROUP -> library.copy(groups = library.groups.map { if (it.id == resource.id) it.copy(workflows = workflows(it.workflows)) else it })
            else -> error("非调用资源")
        }
    }

    private fun valueIssue(field: ExtensionFieldDescriptor, value: String?): String? {
        if (value == null) return if (field.nullable) null else "此字段不允许继承或空值"
        if (field.required && value.isBlank()) return "必填字段不能为空"
        if (field.target.field == ExtensionField.ENTRY_ENABLED && field.choices.map { it.value } == listOf("true") && value == "false")
            return "基础角色字段固定启用，不能停用；可在原编辑器清空正文"
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
        listOf(Issue(ResourceLocations.path(ResourceLocations.fromField(target)), message)))
}
