package dev.gray.companion.core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SillyTavernImportTest {
    @Test fun curatedPresetKeepsMarkerOrderAndDepthOneAroundCurrentInput() = runBlocking {
        val envelope = """{
          "format":"sillytavern.preview.v1", "sanitized":true,
          "character":{"data":{"name":"角色","description":"朋友 {{user}}","first_mes":"你好 {{user}}"}},
          "world":{"entries":{"5":{"uid":5,"comment":"咖啡","constant":false,"disable":false,
            "position":4,"depth":1,"order":1,"key":["coffee"],"content":"记住咖啡"}}},
          "notes":["仅导入安全内容"],
          "preset":{"name":"预设","wi_format":"{0}","prompts":[
            {"identifier":"before","name":"前","role":"system","content":"前","injection_position":0},
            {"identifier":"chatHistory","name":"历史","content":""},
            {"identifier":"depth","name":"深度","role":"user","content":"深度","injection_position":1,"injection_depth":1},
            {"identifier":"after","name":"后","role":"system","content":"后","injection_position":0}
          ],"prompt_order":[{"character_id":100001,"order":[
            {"identifier":"before","enabled":true},{"identifier":"chatHistory","enabled":true},
            {"identifier":"depth","enabled":true},{"identifier":"after","enabled":true}
          ]}]}
        }"""
        val imported = SillyTavernImport.convert(envelope, Library(user = UserIdentity(name = "甲")))
        val bundle = imported.library
        val session = bundle.sessions.single()
        assertEquals("你好 甲", session.messages.single().text)
        val node = bundle.mainCalls.single().workflows.first().nodes.single()
        assertTrue(node.includeHistory && node.explicitHistory)
        val current = session.copy(messages = session.messages + ChatMessage(id = "current", role = Role.USER, text = "coffee"))
        val preview = PromptCompiler().preview(bundle, current, node, "coffee")
        assertTrue(preview.issues.none { it.error })
        assertEquals(listOf("前", "你好 甲", "深度", "coffee", "后"), preview.messages.map { it.text })
        val depthNode = bundle.groups.single().workflows.single().nodes.single()
        assertEquals("1", depthNode.arguments["depth"])
        assertTrue(depthNode.enabled && RecognitionEvaluator.matches(depthNode.recognition) { "coffee" })
        assertEquals("status", bundle.mainCalls.single().workflows.last().nodes.single().outputKey)
        assertEquals(2, bundle.mainCalls.single().tiles.count { it.kind == MacroTileKind.STATUS })
        val fields = bundle.mainCalls.single().tiles.first().fields
        assertEquals("地点", fields.first().title)
        assertEquals(1.0, fields.last().minimum, 0.0)
        assertEquals(7.0, fields.last().maximum, 0.0)
        assertTrue(imported.warnings.contains("导入说明：仅导入安全内容"))
        assertTrue(imported.warnings.none { it.contains("格式分隔符") })
        // The imported status contract requires every declared field, not only location.
        val statusReply = "<StatusBlock><location>咖啡馆</location><date>2026-10-03</date>" +
            "<time>下午</time><weather>晴</weather><top>衬衫</top><bottom>长裤</bottom>" +
            "<shoes>运动鞋</shoes><pose>坐着</pose><posture>交谈</posture><state>平静</state>" +
            "<closeup>微笑</closeup><person>甲和角色</person><love>42</love><level>1</level></StatusBlock>"
        val gatewayCalls = mutableListOf<List<ChatMessage>>()
        val gateway = object : ModelGateway {
            override suspend fun generate(profile: ModelProfile, messages: List<ChatMessage>,
                                          onDelta: suspend (String) -> Unit): String {
                gatewayCalls += messages
                return if (gatewayCalls.size == 1) "好的" else
                    "前缀${statusReply}后缀"
            }
        }
        val profile = ModelProfile(id = "model", model = "test")
        val active = bundle.copy(profiles = listOf(profile), selectedProfileId = profile.id,
            sessions = listOf(current))
        val result = WorkflowEngine().run(active, current, "coffee", gateway)
        assertEquals(result.traces.toString(), RunStatus.SUCCESS, result.status)
        assertEquals(2, gatewayCalls.size)
        assertTrue(gatewayCalls.first().any { it.text == "记住咖啡" })
        assertTrue(gatewayCalls.last().any { it.text == "好的" })
        assertEquals("好的", result.replies.single().text)
        assertEquals(statusReply, result.results["status"])
        val rejected = runCatching { SillyTavernImport.convert(envelope.replace("\"sanitized\":true", "\"sanitized\":false"), Library()) }
        assertTrue(rejected.isFailure)
    }
}
