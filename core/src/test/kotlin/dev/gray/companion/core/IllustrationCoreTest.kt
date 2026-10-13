package dev.gray.companion.core

import org.junit.Assert.*
import org.junit.Test

class IllustrationCoreTest {
    private val user = ChatMessage(id = "u1", role = Role.USER, text = "请继续")
    private val answer = ChatMessage(id = "a1", role = Role.ASSISTANT, turnId = "u1", text = "第一段。\n\n```text\n代码内空行\n\n仍是同一段\n```\n\n末段。")
    private val config = IllustrationConfig(enabled = true, modelProfileId = "model")
    private val session = Session(id = "s1", characterId = "card", mainCallId = "main", messages = listOf(user, answer))
    private val library = Library(characters = listOf(CharacterCard(id = "card", namespace = "hero", name = "角色")),
        mainCalls = listOf(MainCall(id = "main", name = "主调用")), profiles = listOf(ModelProfile(id = "model", model = "offline")),
        sessions = listOf(session), illustration = config)

    @Test fun paragraphsKeepFencedBlankLinesAndStrictXmlAnchors() {
        val paragraphs = IllustrationCore.paragraphs(answer.text)
        assertEquals(listOf("p1", "p2", "p3"), paragraphs.map { it.id })
        assertTrue(paragraphs[1].text.contains("代码内空行\n\n仍是同一段"))
        val one = "<illustrations><image after=\"p2\"><prompt>夜色中的角色</prompt></image></illustrations>"
        assertEquals(listOf(IllustrationPrompt("p2", "夜色中的角色")), IllustrationCore.parseXml(one, paragraphs, 1))
        assertTrue(IllustrationCore.parseXml("<illustrations/>", paragraphs, 1).isEmpty())
        for (invalid in listOf(
            "<illustrations><image after=\"p2\">夜色中的角色</image></illustrations>",
            "<illustrations><image after=\"p4\"><prompt>x</prompt></image></illustrations>",
            "<illustrations><image after=\"p1\"><prompt>x</prompt></image><image after=\"p1\"><prompt>y</prompt></image></illustrations>",
            "<!DOCTYPE x [<!ENTITY a SYSTEM \"file:///tmp/a\">]><illustrations/>",
            "<illustrations/>额外文字"
        )) assertTrue(invalid, runCatching { IllustrationCore.parseXml(invalid, paragraphs, 1) }.isFailure)
    }

    @Test fun planningUsesExactBodyRevisionAndOnlyLatestAssistantMarkers() {
        val plan = IllustrationCore.prepare(library, session, answer)!!
        assertEquals("model", plan.profile.id)
        assertEquals(3, plan.paragraphs.size)
        assertTrue(plan.messages.any { it.role == Role.ASSISTANT && it.text.contains("[p1]") })
        assertTrue(plan.messages.first().text.contains("<prompt>"))
        assertTrue(IllustrationCore.isCurrentSource(session, plan.source))
        val next = session.copy(messages = session.messages + ChatMessage(id = "u2", role = Role.USER, text = "下一题"))
        assertNull(IllustrationCore.prepare(library, next, answer))
        assertTrue("An earlier still-active BODY can finish in the background", IllustrationCore.isCurrentSource(next, plan.source))
        val edited = session.copy(messages = listOf(user, answer.copy(text = "改写", activeTextRevisionId = "r2")))
        assertFalse(IllustrationCore.isCurrentSource(edited, plan.source))
        assertNotEquals(IllustrationCore.sourceKey(answer), IllustrationCore.sourceKey(answer.copy(activeTextRevisionId = "r2")))
    }

    @Test fun savedBlockConversionPreservesLegacyRequestAndCanReorderRoles() {
        val old = IllustrationCore.prepare(library, session, answer)!!
        val converted = IllustrationCore.editableConfig(config)
        assertNull(config.blocks)
        assertEquals(3, converted.blocks?.size)
        val blockLibrary = library.copy(illustration = converted)
        val modern = IllustrationCore.prepare(blockLibrary, session, answer)!!
        assertEquals(old.messages.map { it.role to it.text }, modern.messages.map { it.role to it.text })
        val custom = converted.copy(blocks = listOf(
            PromptBlock(id = "intro", title = "引导", role = Role.USER, template = "请根据这段正文规划插图"),
            PromptBlock(id = "body", title = "正文", template = "{{state.illustration_paragraphs}}"),
        ))
        val customPlan = IllustrationCore.prepare(library.copy(illustration = custom), session, answer)!!
        assertEquals(listOf(Role.USER, Role.SYSTEM), customPlan.messages.map { it.role })
        assertTrue(customPlan.messages.last().text.contains("[p1]"))
        assertFalse(customPlan.messages.any { it.text == user.text })
    }

    @Test fun wire37KeepsDisplayAnnotationsOutsideSession() {
        val image = IllustrationImage("s1", "a1", IllustrationCore.sourceKey(answer), "p1", "images/one.png", 1024, 1280)
        val encoded = LibraryFormat.encode(library.copy(illustrationImages = listOf(image)))
        val restored = LibraryFormat.decode(encoded)
        assertEquals(37, restored.schemaVersion)
        assertEquals(config, restored.illustration)
        assertEquals(listOf(image), restored.illustrationImages)
        assertEquals(session, restored.sessions.single())
        assertTrue(IllustrationCore.validate(config).isEmpty())
        assertTrue(IllustrationCore.validate(config.copy(maxImages = 5)).any { it.error })
    }
}
