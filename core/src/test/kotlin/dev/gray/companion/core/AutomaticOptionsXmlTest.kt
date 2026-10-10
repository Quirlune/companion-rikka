package dev.gray.companion.core

import org.junit.Assert.*
import org.junit.Test

class AutomaticOptionsXmlTest {
    @Test fun oldAbsentSchemaPreservesLiteralOptionText() {
        val config = AutomaticOptionsConfig(count = 2)
        assertEquals(listOf(AutomaticOption("A & B"), AutomaticOption("&amp;")),
            AutomaticOptions.parse("<option>A & B</option><option>&amp;</option>", config))
        val loaded = LibraryFormat.json.decodeFromString(AutomaticOptionsConfig.serializer(), "{\"count\":2}")
        assertEquals(config, loaded)
    }

    @Test fun customTagsTitlesAndEntitiesYieldOnlyUsableContent() {
        val config = AutomaticOptionsConfig(count = 2, titlesEnabled = true,
            items = listOf(AutomaticOptionSpec("reply_one", "heading_one"), AutomaticOptionSpec("reply_two", "heading_two")))
        val text = "<heading_one>先问好</heading_one><reply_one>A &amp; B</reply_one>\n" +
            "<heading_two>说再见</heading_two><reply_two><![CDATA[<再见>]]></reply_two>"
        assertEquals(listOf(AutomaticOption("A & B", "先问好"), AutomaticOption("<再见>", "说再见")),
            AutomaticOptions.parse(text, config))
        assertEquals(listOf(AutomaticOption("one", "first"), AutomaticOption("two", "second")),
            AutomaticOptions.parse("<reply_two>two</reply_two><heading_one>first</heading_one><reply_one>one</reply_one><heading_two>second</heading_two>", config))
        assertTrue(AutomaticOptions.protocol(config).contains("<heading_two>"))
        assertTrue(AutomaticOptions.protocol(config).contains("<reply_two>"))
    }

    @Test fun repeatedDefaultNamesFollowOccurrenceOrderAndRetainDormantSpecs() {
        val config = AutomaticOptionsConfig(count = 2, items = listOf(AutomaticOptionSpec(), AutomaticOptionSpec(), AutomaticOptionSpec("later")))
        assertEquals(listOf(AutomaticOption("one"), AutomaticOption("two")),
            AutomaticOptions.parse("<option>one</option><option>two</option>", config))
        assertEquals("later", AutomaticOptions.effectiveItems(config.copy(count = 3))[2].contentTag)
    }

    @Test fun rejectsMissingEmptyExtraMalformedAndUnsafeXml() {
        val config = AutomaticOptionsConfig(count = 1, titlesEnabled = true,
            items = listOf(AutomaticOptionSpec("reply", "title")))
        val invalid = listOf(
            "<title>title</title>", "<title></title><reply>x</reply>",
            "<title>title</title><reply> </reply>", "<title>x</title><title>duplicate</title>",
            "<title>title</title><reply>x</reply><reply>extra</reply>",
            "<title>title</title><reply><child>x</child></reply>",
            "<title>title</title><reply x='1'>x</reply>",
            "outside<title>title</title><reply>x</reply>",
            "<!DOCTYPE x [<!ENTITY sample 'x'>]><title>title</title><reply>&sample;</reply>",
            "<title>title</title><reply>x", "<title>title</title><reply>&unknown;</reply>",
            "<title>${"x".repeat(121)}</title><reply>x</reply>")
        invalid.forEach { text ->
            assertTrue(text, runCatching { AutomaticOptions.parse(text, config) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

    @Test fun editableDefaultsReferenceProtocolAndCustomBlocksOnlyChangeExplicitly() {
        val config = AutomaticOptionsConfig(count = 1, items = listOf(AutomaticOptionSpec("answer")))
        val editable = AutomaticOptions.editableConfig(config)
        assertEquals(AutomaticOptions.PROTOCOL_MACRO, editable.blocks!!.first { it.id == "automatic-options-format" }.template)
        val custom = editable.copy(blocks = listOf(PromptBlock(id = "custom", template = "my own prompt")))
        assertEquals(custom, AutomaticOptions.editableConfig(custom))
        val synced = AutomaticOptions.withCurrentProtocol(custom)
        assertEquals("my own prompt", synced.blocks!!.first().template)
        assertEquals(AutomaticOptions.PROTOCOL_MACRO, synced.blocks!!.last().template)
    }

    @Test fun importedFormatBlockIsReplacedWithoutAppendingOrChangingTask() {
        val task = PromptBlock(id = "imported-task", template = "Keep my task")
        val format = PromptBlock(id = "imported-format", title = "输出格式", template = "old fixed tags")
        val custom = AutomaticOptionsConfig(blocks = listOf(task, format))
        val synced = AutomaticOptions.withCurrentProtocol(custom).blocks!!
        assertEquals(2, synced.size)
        assertEquals(task, synced.first())
        assertEquals(format.copy(template = AutomaticOptions.PROTOCOL_MACRO), synced.last())
        val slugFormat = format.copy(id = "by-slug", title = "自定义协议", slug = "format")
        val preferred = AutomaticOptions.withCurrentProtocol(custom.copy(blocks = listOf(task, format, slugFormat))).blocks!!
        assertEquals(format, preferred[1])
        assertEquals(slugFormat.copy(template = AutomaticOptions.PROTOCOL_MACRO), preferred[2])
    }

    @Test fun protocolMacroIsRequestLocalAndNoHiddenInstructionsAreAppended() {
        val node = WorkflowNode(id = "body", visible = true)
        val flow = Workflow(id = "primary", nodes = listOf(node))
        val main = MainCall(id = "main", workflows = listOf(flow), primaryWorkflowId = flow.id, specialDefault = true)
        val profile = ModelProfile(id = "model", model = "test")
        val config = AutomaticOptionsConfig(enabled = true, count = 1, items = listOf(AutomaticOptionSpec("answer")),
            blocks = listOf(PromptBlock(id = "format", template = AutomaticOptions.PROTOCOL_MACRO)))
        val library = Library(mainCalls = listOf(main), profiles = listOf(profile), selectedProfileId = profile.id, automaticOptions = config)
        val session = Session(id = "chat", mainCallId = main.id, state = mapOf("automatic_options_protocol" to "stale"), messages = listOf(
            ChatMessage(id = "user", text = "hello"), ChatMessage(id = "reply", role = Role.ASSISTANT, text = "hi", turnId = "user", workflowId = flow.id, nodeId = node.id)))
        val request = AutomaticOptions.request(library, session)!!
        assertEquals(listOf(AutomaticOptions.protocol(config)), request.messages.map { it.text })
        val free = config.copy(blocks = listOf(PromptBlock(template = "only this")))
        assertEquals(listOf("only this"), AutomaticOptions.request(library.copy(automaticOptions = free), session)!!.messages.map { it.text })
        assertEquals("stale", session.state["automatic_options_protocol"])
    }
}
