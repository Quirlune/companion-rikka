package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.encodeToJsonElement
import org.junit.Assert.*
import org.junit.Test

class CardDisplaysTest {
    @Test fun everyPresentationCanBeExplicitlyPlacedInTopMenu() {
        CardPresentation.entries.forEach { presentation ->
            assertTrue(CardRegion.MENU in CardDisplays.allowedRegions(presentation))
            val card = node(CardDefinition("菜单卡片", presentation = presentation, defaultRegion = CardRegion.MENU))
            assertEquals(CardRegion.MENU, CardDisplays.effectivePlacements(library(card), session()).single().region)
        }
    }

    private val gateway = object : ModelGateway {
        override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>,
                                      onDelta: suspend (String) -> Unit): String = error("Card checks must not request a model")
    }
    private fun node(definition: CardDefinition = CardDefinition("状态", fields = listOf(
        CardFieldDefinition("content", "内容")))) = WorkflowNode(id = "card", title = "卡片",
        actionId = "card.define", outputKey = "card-value", includeHistory = false,
        recordTrajectory = false, cardDefinition = definition)
    private fun session() = Session(id = "session", characterId = "character", mainCallId = "main",
        messages = listOf(ChatMessage(id = "user", role = Role.USER, text = "hello")))
    private fun library(node: WorkflowNode, session: Session = session(), extra: List<WorkflowNode> = emptyList()) =
        LibraryFormat.upgrade(Library(
            characters = listOf(CharacterCard(id = "character", namespace = "hero", name = "Hero")),
            mainCalls = listOf(MainCall(id = "main", name = "Main", workflows = listOf(
                Workflow(id = "flow", nodes = listOf(node) + extra)))),
            sessions = listOf(session), selectedSessionId = session.id))
    private fun context(node: WorkflowNode, library: Library, session: Session, input: String = "value") =
        ActionContext(node, input, emptyList(), emptyMap(), session.state, session.results,
            null, gateway, {}, session = session, library = library, workflowId = "flow")
    private fun snapshot(node: WorkflowNode, value: String = "value"): CardSnapshot {
        val definition = requireNotNull(node.cardDefinition)
        return CardSnapshot(formatVersion = 2, nodeId = node.id, title = definition.title,
            minimumWidthDp = definition.minimumWidthDp,
            fields = definition.fields.map { CardSnapshotField(it.key, it.title, it.kind, value,
                minimum = it.minimum, maximum = it.maximum, compactLimit = it.compactLimit) },
            presentation = definition.presentation)
    }
    private fun accepted(node: WorkflowNode, snapshot: CardSnapshot = snapshot(node)) = session().copy(
        results = mapOf(node.outputKey to CardDisplays.encodeSnapshot(snapshot)),
        resultOrigins = mapOf(node.outputKey to ResultOrigin("main", "flow", node.id)))

    @Test fun restoredBranchUsesOneFrozenDefinitionForCardDiscoveryAndValue() {
        val card = node()
        val accepted = accepted(card)
        val original = library(card, accepted)
        val restored = accepted.copy(executableWorld = CompletedWorlds.capture(original, accepted, "hello"))
        val changed = library(card.copy(enabled = false, outputKey = "new-card-value"), restored)
        val roundTrip = LibraryFormat.json.decodeFromString(Library.serializer(),
            LibraryFormat.json.encodeToString(Library.serializer(), changed))
        val branch = roundTrip.sessions.single()
        assertEquals(card.outputKey, CardDisplays.configured(roundTrip, branch).single().outputKey)
        assertEquals("value", CardDisplays.resolve(roundTrip, branch).single().snapshot!!.fields.single().value)
        // Live chats still respect the new disabled definition; restoring never rewrites it.
        assertTrue(CardDisplays.resolve(roundTrip, branch.copy(executableWorld = null)).isEmpty())
        assertFalse(roundTrip.mainCalls.single().workflows.single().nodes.single().enabled)
    }

    @Test fun registeredHandlerReadsCapturedMacrosRegexListsAndExactNumbers() = runBlocking {
        val node = node(CardDefinition("状态", fields = listOf(
            CardFieldDefinition("name", "姓名", source = "{{character.name}}"),
            CardFieldDefinition("number", "计数", pattern = "number=(\\d+)", captureGroup = 1, kind = CardFieldKind.NUMBER),
            CardFieldDefinition("progress", "进度", source = "{{state.level}}", kind = CardFieldKind.PROGRESS),
            CardFieldDefinition("items", "物品", pattern = "item=([^;]+)", captureGroup = 1, kind = CardFieldKind.LIST),
            CardFieldDefinition("missing", "待填", pattern = "absent=(\\d+)", captureGroup = 1, kind = CardFieldKind.NUMBER))))
        val session = session().copy(state = mapOf("level" to "42.500"))
        val library = library(node, session)
        val registry = ActionRegistry.defaults()
        assertEquals(ActionPresentation.STANDARD, registry.descriptors().single { it.id == "card.define" }.presentation)
        val outcome = registry.handler("card.define")!!.execute(context(node, library, session,
            "number=9007199254740993;item=茶;item=书;"))
        val snapshot = CardDisplays.decodeSnapshot(outcome.text, node.id)
        assertEquals(listOf("Hero", "9007199254740993", "42.5", "", ""), snapshot.fields.map { it.value })
        assertEquals(listOf("茶", "书"), snapshot.fields[3].items)
        assertFalse(outcome.hasEffects())
        assertNull(CardDisplays.validateOutcome(node, outcome.text))
    }

    @Test fun displayRequiresOwnerActivationLoadedWorkflowAndNode() {
        val node = node()
        val session = accepted(node)
        val library = library(node, session)
        assertNotNull(CardDisplays.resolve(library, session).single().snapshot)
        val wrongOwner = session.copy(resultOrigins = mapOf(node.outputKey to ResultOrigin("main", "other-flow", node.id)))
        assertNotNull(CardDisplays.resolve(library, wrongOwner).single().error)
        val disabledMacro = session.copy(macroHistory = mapOf("result.${node.outputKey}" to listOf(
            MacroVersion(1, session.results.getValue(node.outputKey), session.resultOrigins.getValue(node.outputKey), enabled = false))))
        assertTrue(CardDisplays.resolve(library, disabledMacro).isEmpty())
        assertNull(CardDisplays.resolve(library, session.copy(unavailableResultKeys = setOf(node.outputKey))).single().snapshot)
        val disabled = library(node.copy(enabled = false), session)
        assertFalse(CardDisplays.configured(disabled, session).single().enabled)
        assertTrue(CardDisplays.resolve(disabled, session).isEmpty())
        val groupLibrary = library.copy(mainCalls = library.mainCalls.map { it.copy(workflows = emptyList()) },
            groups = listOf(CallGroup(id = "group", name = "Group", workflows = library.mainCalls.single().workflows)))
        assertTrue(CardDisplays.configured(groupLibrary, session).isEmpty())
        val loaded = session.copy(groupIds = listOf("group"))
        assertEquals("group", CardDisplays.configured(groupLibrary, loaded).single().resourceId)
        val disabledWorkflow = groupLibrary.copy(groups = groupLibrary.groups.map { group ->
            group.copy(workflows = group.workflows.map { it.copy(enabled = false) }) })
        assertTrue(CardDisplays.resolve(disabledWorkflow, loaded).isEmpty())
    }

    @Test fun currentBodySourceBindsOnlyAcceptedPrimaryFloorAndRevision() = runBlocking {
        val node = node()
        val user = session().messages.single()
        val body = ChatMessage(id = "body", role = Role.ASSISTANT, text = "原正文",
            turnId = user.id, workflowId = "flow", nodeId = "model")
        val unrelated = body.copy(id = "ordinary", text = "普通调用输出", workflowId = "ordinary")
        val source = session().copy(messages = listOf(user, body, unrelated))
        val library = library(node, source)
        assertEquals(body.id, CardDisplays.currentPrimaryBody(library, source)?.id)

        val generated = CardDisplays.decodeSnapshot(CardDisplays.define(context(node, library, source)).text)
        assertEquals(3, generated.formatVersion)
        assertEquals(HistoryMemory.source(body), generated.bodySource)
        assertNull(CardDisplays.validateOutcome(node, CardDisplays.encodeSnapshot(generated),
            context(node, library, source)))

        val origin = ResultOrigin("main", "flow", node.id)
        val accepted = source.copy(results = mapOf(node.outputKey to CardDisplays.encodeSnapshot(generated)),
            resultOrigins = mapOf(node.outputKey to origin), macroHistory = mapOf(
                "result.${node.outputKey}" to listOf(MacroVersion(1,
                    CardDisplays.encodeSnapshot(generated), origin, turn = 1, turnId = user.id))))
        val resolved = CardDisplays.resolve(library(node, accepted), accepted).single()
        assertEquals(user.id, resolved.acceptedTurnId)
        assertTrue(CardDisplays.belongsBelowBody(resolved, body))
        assertFalse(CardDisplays.belongsBelowBody(resolved, unrelated))
        assertFalse(CardDisplays.belongsBelowBody(resolved, body.copy(text = "手动改过",
            activeTextRevisionId = "edited")))
        val unanswered = accepted.copy(messages = accepted.messages + ChatMessage(id = "next",
            role = Role.USER, text = "下一句"))
        assertNull(CardDisplays.currentPrimaryBody(library(node, unanswered), unanswered))
        assertNotNull(CardDisplays.validateOutcome(node, CardDisplays.encodeSnapshot(generated.copy(
            bodySource = HistoryMemory.source(unrelated))), context(node, library, source)))
    }

    @Test fun selectedGenerationVariantRestoresItsOwnFloorBoundCard() {
        val node = node()
        val user = session().messages.single()
        val firstBody = ChatMessage(id = "body-one", role = Role.ASSISTANT, text = "第一候选",
            turnId = user.id, workflowId = "flow", nodeId = "model")
        val secondBody = firstBody.copy(id = "body-two", text = "第二候选")
        val origin = ResultOrigin("main", "flow", node.id)
        val firstText = CardDisplays.encodeSnapshot(snapshot(node).copy(bodySource = HistoryMemory.source(firstBody)))
        val secondText = CardDisplays.encodeSnapshot(snapshot(node).copy(bodySource = HistoryMemory.source(secondBody)))
        val address = "result.${node.outputKey}"
        val firstVersion = MacroVersion(1, firstText, origin, turn = 1, turnId = user.id)
        val secondVersion = MacroVersion(1, secondText, origin, turn = 1, turnId = user.id)
        val first = TurnVariant(id = "one", replyIds = listOf(firstBody.id),
            after = RuntimeSnapshot(results = mapOf(node.outputKey to firstText),
                resultOrigins = mapOf(node.outputKey to origin)), macroAppends = mapOf(address to listOf(firstVersion)))
        val second = TurnVariant(id = "two", replies = listOf(secondBody),
            after = RuntimeSnapshot(results = mapOf(node.outputKey to secondText),
                resultOrigins = mapOf(node.outputKey to origin)), macroAppends = mapOf(address to listOf(secondVersion)))
        val selected = session().copy(messages = listOf(user, firstBody),
            results = first.after.results, resultOrigins = first.after.resultOrigins,
            macroHistory = mapOf(address to listOf(firstVersion)),
            turnRecords = listOf(TurnRecord(user.id, TurnCheckpoint(RuntimeSnapshot()),
                variants = listOf(first, second), selectedVariantId = first.id)))
        val changed = requireNotNull(MessageOperations.applyLatestVariant(selected, user.id, second.id).session)
        assertEquals(secondBody.id, CardDisplays.currentPrimaryBody(library(node, changed), changed)?.id)
        val resolved = CardDisplays.resolve(library(node, changed), changed).single()
        assertEquals(secondText, changed.results[node.outputKey])
        assertTrue(CardDisplays.belongsBelowBody(resolved, secondBody))
        assertFalse(CardDisplays.belongsBelowBody(resolved, firstBody))
    }

    @Test fun legacyCardRequiresProvenTurnAndOriginalBody() {
        val node = node()
        val user = session().messages.single()
        val body = ChatMessage(id = "body", role = Role.ASSISTANT, text = "原文",
            turnId = user.id, workflowId = "flow")
        val text = CardDisplays.encodeSnapshot(snapshot(node).copy(formatVersion = 1))
        val origin = ResultOrigin("main", "flow", node.id)
        val base = session().copy(messages = listOf(user, body), results = mapOf(node.outputKey to text),
            resultOrigins = mapOf(node.outputKey to origin))
        val oldVersion = MacroVersion(1, text, origin, turn = 1, turnId = user.id)
        val proven = base.copy(macroHistory = mapOf("result.${node.outputKey}" to listOf(oldVersion)))
        assertTrue(CardDisplays.belongsBelowBody(CardDisplays.resolve(library(node, proven), proven).single(), body))
        assertFalse(CardDisplays.belongsBelowBody(CardDisplays.resolve(library(node, base), base).single(), body))
        assertFalse(CardDisplays.belongsBelowBody(CardDisplays.resolve(library(node, proven), proven).single(),
            body.copy(activeTextRevisionId = "edited")))
        val copied = proven.copy(macroHistory = mapOf("result.${node.outputKey}" to listOf(
            oldVersion.copy(valueOrigin = origin))))
        assertFalse(CardDisplays.belongsBelowBody(CardDisplays.resolve(library(node, copied), copied).single(), body))
    }

    @Test fun acceptedSnapshotRemainsSelfContainedAfterDefinitionEditAndRuntimeRollback() {
        val node = node(CardDefinition("原侧栏", fields = listOf(CardFieldDefinition("content", "旧标签")),
            presentation = CardPresentation.SIDE_PANEL))
        val old = accepted(node).copy(cardPlacements = listOf(CardPlacement(node.id, CardRegion.SIDEBAR, collapsed = true)))
        val edited = node.copy(cardDefinition = node.cardDefinition!!.copy(title = "新卡片", presentation = CardPresentation.CARD,
            fields = listOf(CardFieldDefinition("different", "新标签", source = "新来源"))))
        val restored = old.copy(results = mapOf(node.outputKey to "new"), state = mapOf("new" to "state"))
            .withRuntime(old.runtimeSnapshot())
        assertEquals(old.cardPlacements, restored.cardPlacements)
        val snapshot = CardDisplays.resolve(library(edited, restored), restored).single().snapshot!!
        assertEquals("原侧栏", snapshot.title)
        assertEquals(CardPresentation.SIDE_PANEL, snapshot.presentation)
        assertEquals("旧标签", snapshot.fields.single().title)
        assertEquals("value", snapshot.fields.single().value)
        assertFalse(CardRegion.INPUT in CardDisplays.allowedRegions(snapshot.presentation))
    }

    @Test fun savedConfigurationAndManualOutcomeCannotBypassTypedContract() {
        val node = node(CardDefinition("数值", fields = listOf(CardFieldDefinition("count", "计数", kind = CardFieldKind.NUMBER))))
        val library = library(node)
        val context = context(node, library, session())
        val ref = PlannedNode(library.mainCalls.single().workflows.single(), node,
            ActionRegistry.defaults().snapshot().getValue("card.define"))
        val validator = OutcomeValidator(library, session())
        val valid = CardDisplays.encodeSnapshot(snapshot(node, "12").copy(formatVersion = 3))
        assertEquals(valid, validator.validate(ref, context, ActionOutcome(valid)).text)
        val malformed = LibraryFormat.json.encodeToString(CardSnapshot.serializer(), snapshot(node, "NaN"))
        for (text in listOf("plain manual text", malformed,
            LibraryFormat.json.encodeToString(CardSnapshot.serializer(), snapshot(node, "12").copy(nodeId = "other")))) {
            try { validator.validate(ref, context, ActionOutcome(text)); fail("MANUAL must be checked") }
            catch (failure: NodeExecutionFailure) { assertEquals(RecoveryFailureKind.OUTPUT_FORMAT, failure.kind) }
        }
        try { validator.validate(ref, context, ActionOutcome(valid, stateWrites = mapOf("forged" to "write"))); fail("No effects") }
        catch (failure: NodeExecutionFailure) { assertEquals(RecoveryFailureKind.CONFIGURATION, failure.kind) }
        for (invalid in listOf(node.copy(visible = true),
            node.copy(outputProcessing = listOf(TextProcessStep(TextProcessKind.TRIM))),
            node.copy(regexOutputCopy = RegexOutputCopy(enabled = true, outputKey = "copy", pattern = ".*")),
            node.copy(cardDefinition = node.cardDefinition!!.copy(fields = listOf(CardFieldDefinition("x", "X", pattern = "(")))))) {
            assertTrue(LibraryValidator.validate(library(invalid)).any { it.error && ("cardDefinition" in it.path ||
                it.path.endsWith("visible") || it.path.endsWith("outputProcessing") || it.path.endsWith("regexOutputCopy")) })
        }
        val duplicate = library.copy(mainCalls = library.mainCalls.map { main -> main.copy(workflows = main.workflows +
            Workflow(id = "flow2", nodes = listOf(node.copy(outputKey = "other-output")))) })
        assertTrue(LibraryValidator.validate(duplicate).any { it.path == "library.cardNodeIds" })
        val oldOrdinaryNode = duplicate.copy(mainCalls = duplicate.mainCalls.map { main -> main.copy(workflows = main.workflows.map {
            if (it.id == "flow2") it.copy(nodes = listOf(node.copy(actionId = "text.render", cardDefinition = null))) else it
        }) })
        assertFalse(LibraryValidator.validate(oldOrdinaryNode).any { it.path == "library.cardNodeIds" })
    }

    @Test fun layoutCASPreservesRuntimeOtherSessionsAndOrphanPreferences() {
        val node = node(CardDefinition("侧栏", fields = listOf(CardFieldDefinition("content", "内容")),
            presentation = CardPresentation.SIDE_PANEL))
        val session = accepted(node).copy(state = mapOf("kept" to "state"))
        val other = session.copy(id = "other", results = mapOf("other" to "kept"))
        val library = library(node, session).copy(sessions = listOf(session, other))
        assertTrue(session.cardPlacements.isEmpty())
        assertNull(CardLayoutCommands.update(library, session.id, emptyList(), listOf(CardPlacement(node.id, CardRegion.INPUT))).library)
        val next = listOf(CardPlacement(node.id, CardRegion.SIDEBAR, collapsed = true), CardPlacement("unloaded-node", CardRegion.INPUT, 1))
        val result = CardLayoutCommands.update(library, session.id, emptyList(), next)
        val updated = requireNotNull(result.library)
        assertEquals(session.runtimeSnapshot(), updated.sessions.first().runtimeSnapshot())
        assertEquals(session.messages, updated.sessions.first().messages)
        assertSame(other, updated.sessions[1])
        assertEquals(library.mainCalls, updated.mainCalls)
        assertNull(CardLayoutCommands.update(updated, session.id, emptyList(), next).library)
        assertNull(CardLayoutCommands.update(library.copy(selectedSessionId = other.id), session.id, emptyList(), next).library)
        assertTrue(LibraryValidator.validate(updated).none { it.error })
        assertEquals(listOf(CardRegion.CHAT, CardRegion.INPUT, CardRegion.MENU), CardDisplays.allowedRegions(CardPresentation.STRIP))
    }

    @Test fun portableConfigurationStrictUndoAndOldWireKeepSessionPreferences() {
        val node = node()
        val base = library(node, accepted(node).copy(cardPlacements = listOf(CardPlacement(node.id, CardRegion.CHAT))))
        val imported = base.mainCalls.single().copy(workflows = base.mainCalls.single().workflows.map { workflow ->
            workflow.copy(nodes = workflow.nodes.map { it.copy(cardDefinition = it.cardDefinition!!.copy(title = "导入标题")) }) })
        val json = LibraryFormat.json.encodeToJsonElement(MainCall.serializer(), imported)
        val preview = ConfigurationCommands.preview("""{"format":"companion.config.v1","resources":{"mainCalls":[$json]}}""", base)
        assertTrue(preview.issues.toString(), preview.issues.none { it.error })
        val applied = ConfigurationCommands.apply(requireNotNull(preview.plan), base)
        assertEquals(base.sessions, applied.library!!.sessions)
        val undone = ConfigurationCommands.undo(requireNotNull(applied.receipt), applied.library!!)
        assertEquals(base.mainCalls, undone.library!!.mainCalls)
        assertEquals(base.sessions, undone.library!!.sessions)
        val later = applied.library!!.copy(mainCalls = listOf(imported.copy(name = "Later edit")))
        assertNull(ConfigurationCommands.undo(applied.receipt!!, later).library)
        val oldMain = base.mainCalls.single().copy(workflows = listOf(Workflow(id = "flow", nodes = listOf(
            node.copy(actionId = "text.render", cardDefinition = null)))))
        val oldDocument = LibraryFormat.json.encodeToJsonElement(Library.serializer(), base.copy(mainCalls = listOf(oldMain))) as JsonObject
        val oldSessions = JsonArray((oldDocument.getValue("sessions") as JsonArray).map {
            JsonObject((it as JsonObject).filterKeys { key -> key != "cardPlacements" })
        })
        val oldMains = JsonArray((oldDocument.getValue("mainCalls") as JsonArray).map { main ->
            val fields = (main as JsonObject).toMutableMap()
            fields["workflows"] = JsonArray((fields.getValue("workflows") as JsonArray).map { workflow ->
                val workflowFields = (workflow as JsonObject).toMutableMap()
                workflowFields["nodes"] = JsonArray((workflowFields.getValue("nodes") as JsonArray).map {
                    JsonObject((it as JsonObject).filterKeys { key -> key != "cardDefinition" })
                })
                JsonObject(workflowFields)
            })
            JsonObject(fields)
        })
        val oldWire = oldDocument.toMutableMap().apply {
            put("schemaVersion", JsonPrimitive(14))
            put("sessions", oldSessions)
            put("mainCalls", oldMains)
        }
        val decoded = LibraryFormat.decode(JsonObject(oldWire))
        assertEquals(LibraryFormat.CURRENT_VERSION, decoded.schemaVersion)
        assertTrue(decoded.sessions.single().cardPlacements.isEmpty())
        assertNull(decoded.mainCalls.single().workflows.single().nodes.single().cardDefinition)
    }

    @Test fun acceptedCardReceiptReplaysWithoutHandlerExecutionOrRawJsonReplies() = runBlocking {
        val node = node()
        val failure = WorkflowNode(id = "failure", title = "故障", actionId = "test.failure", outputKey = "done",
            dependsOn = listOf(node.id), includeHistory = false)
        val session = session()
        val library = library(node, session, listOf(failure))
        var cardCalls = 0
        var fail = true
        val registry = ActionRegistry.defaults().register(ActionDescriptor("card.define", "定义卡片")) {
            cardCalls++; CardDisplays.define(it)
        }.register(ActionDescriptor("test.failure", "故障")) {
            if (fail) error("offline failure") else ActionOutcome("done")
        }
        val engine = WorkflowEngine(registry)
        val first = engine.run(library, session, "captured input", gateway)
        assertEquals(RunStatus.PAUSED, first.status)
        assertTrue(first.replies.isEmpty())
        assertEquals(1, cardCalls)
        val recovery = requireNotNull(first.recovery)
        assertEquals(first.results.getValue(node.outputKey), recovery.accepted.single().outcome.text)
        val current = session.withRuntime(first.runtimeSnapshot()).copy(pendingRun = recovery,
            macroHistory = first.macroHistory, cardPlacements = listOf(CardPlacement(node.id, CardRegion.CHAT)))
        fail = false
        val resumed = engine.resume(library, current, gateway, recovery,
            RecoveryDecision(RecoveryDecisionKind.RETRY, "flow/failure"))
        assertEquals(RunStatus.SUCCESS, resumed.status)
        assertEquals(1, cardCalls)
        assertEquals(first.results.getValue(node.outputKey), resumed.results.getValue(node.outputKey))
        assertTrue(resumed.replies.isEmpty())
        assertEquals(current.cardPlacements, current.withRuntime(resumed.runtimeSnapshot()).cardPlacements)
    }
}
