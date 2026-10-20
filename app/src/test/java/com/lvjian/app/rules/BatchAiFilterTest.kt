package com.lvjian.app.rules

import com.lvjian.app.ai.AiClient
import com.lvjian.app.data.AIState
import com.lvjian.app.data.AiConfig
import com.lvjian.app.data.MessageEntity
import com.lvjian.app.data.RuleAction
import com.lvjian.app.data.RuleEntity
import com.lvjian.app.data.RuleType
import com.lvjian.app.data.ScopeKeys
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 批量整理：判定和摘要必须来自同一次调用。
 * 用 MockWebServer 走真实 HTTP，验证提示词和解析，不 mock AiClient。
 */
class BatchAiFilterTest {

    private val builtinRule = RuleEntity(
        id = 1,
        name = TriageRules.NAME,
        type = RuleType.AI_PROMPT,
        pattern = TriageRules.PATTERN,
        scopeKeys = ScopeKeys.ALL,
        action = RuleAction.FILTER,
        builtin = true
    )

    private fun message(id: Long, text: String) = MessageEntity(
        id = id,
        appPackage = "com.tencent.mm",
        groupName = "课程群",
        isGroup = true,
        sender = "同学",
        text = text,
        timestamp = 1_700_000_000_000 + id
    )

    private fun responseFor(content: String): MockResponse {
        val body = JSONObject().put(
            "choices",
            JSONArray().put(
                JSONObject().put("message", JSONObject().put("content", content))
            )
        )
        return MockResponse().setResponseCode(200).setBody(body.toString())
    }

    @Test
    fun batchPromptCarriesTriageRulesAndSummaryOutput() = runBlocking {
        val server = MockWebServer()
        var systemPrompt = ""
        var userPrompt = ""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val messages = JSONObject(request.body.readUtf8()).getJSONArray("messages")
                systemPrompt = messages.getJSONObject(0).getString("content")
                userPrompt = messages.getJSONObject(1).getString("content")
                return responseFor(
                    """[{"i":1,"filter":false,"rule":null,"summary":"周五前交线性代数作业"},""" +
                        """{"i":2,"filter":true,"rule":"滤掉无用信息","summary":""}]"""
                )
            }
        }
        server.start()
        try {
            val config = AiConfig("http://127.0.0.1:${server.port}", "test-key", "test-model", true)
            val verdicts = AiClient().judgeBatch(
                config,
                "过滤｜「${TriageRules.NAME}」${TriageRules.PATTERN}",
                "[1] 群：课程群 | 同学：作业周五交\n[2] 群：系统 | 手机：充电完成"
            )

            assertEquals(2, verdicts.size)
            val kept = verdicts[1]!!
            val dropped = verdicts[2]!!
            assertFalse(kept.filter)
            assertEquals("周五前交线性代数作业", kept.summary)
            assertTrue(dropped.filter)
            assertEquals("丢弃的条目不写摘要", "", dropped.summary)

            // 判定准则与摘要要求都在提示词里，否则模型只会给 filter
            assertTrue(systemPrompt.contains("判定原则"))
            assertTrue(systemPrompt.contains("summary"))
            assertTrue(userPrompt.contains("用户的整理规则"))
            assertTrue(userPrompt.contains("[1] 群：课程群"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun batchFilterPassesSummaryToDatabaseCallback() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            responseFor(
                """[{"i":1,"filter":false,"rule":null,"summary":"周五交线代作业"},""" +
                    """{"i":2,"filter":true,"rule":"滤掉无用信息","summary":""}]"""
            )
        )
        server.start()
        try {
            val config = AiConfig("http://127.0.0.1:${server.port}", "test-key", "test-model", true)
            val engine = BatchAiFilter(AiClient(), config)
            val applied = mutableListOf<Triple<Long, Boolean, String>>()
            val result = engine.run(
                listOf(message(1, "作业周五交"), message(2, "充电完成")),
                listOf(builtinRule),
                { _, _ -> }
            ) { msgId, filtered, _, state, summary ->
                assertEquals(AIState.DONE, state)
                applied += Triple(msgId, filtered, summary)
            }

            assertEquals(1, result.filtered)
            assertEquals(1, result.kept)
            assertEquals(0, result.failed)
            assertEquals("周五交线代作业", applied.first { it.first == 1L }.third)
            assertEquals("", applied.first { it.first == 2L }.third)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun progressStartsWithTotalAndAdvancesAfterEachSavedMessage() = runBlocking {
        val server = MockWebServer()
        server.enqueue(responseFor("""[{"i":1,"filter":false,"summary":"第一条"},{"i":2,"filter":true},{"i":3,"filter":false,"summary":"第三条"}]"""))
        server.start()
        try {
            val engine = BatchAiFilter(AiClient(), AiConfig("http://127.0.0.1:${server.port}", "test-key", "test-model", true))
            val progress = mutableListOf<Pair<Int, Int>>()
            val saved = mutableListOf<Long>()
            engine.run((1L..3L).map { message(it, "消息$it") }, listOf(builtinRule), { done, total ->
                assertEquals("只能报告已经保存成功的条数", saved.size, done)
                progress += done to total
            }) { id, _, _, _, _ -> saved += id }
            assertEquals(listOf(0 to 3, 1 to 3, 2 to 3, 3 to 3), progress)
            assertEquals("实时进度不能增加 API 调用次数", 1, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun missingVerdictsStayUnorganizedAndDoNotIncreaseSuccessfulCount() = runBlocking {
        val server = MockWebServer()
        server.enqueue(responseFor("""[{"i":1,"filter":false,"summary":"保留第一条"}]"""))
        server.start()
        try {
            val engine = BatchAiFilter(AiClient(), AiConfig("http://127.0.0.1:${server.port}", "test-key", "test-model", true))
            val progress = mutableListOf<Pair<Int, Int>>()
            val result = engine.run(listOf(message(1, "第一条"), message(2, "第二条")), listOf(builtinRule),
                { done, total -> progress += done to total }) { _, _, _, _, _ -> }
            assertEquals(1, result.kept)
            assertEquals(1, result.failed)
            assertEquals(listOf(0 to 2, 1 to 2, 1 to 2), progress)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun messagesWithoutApplicableRulesAlsoReportEachSavedItem() = runBlocking {
        val progress = mutableListOf<Pair<Int, Int>>()
        val saved = mutableListOf<Long>()
        val result = BatchAiFilter(AiClient(), AiConfig()).run(
            listOf(message(1, "第一条"), message(2, "第二条")), emptyList(),
            { done, total ->
                assertEquals(saved.size, done)
                progress += done to total
            }
        ) { id, _, _, _, _ -> saved += id }
        assertEquals(listOf(0 to 2, 1 to 2, 2 to 2), progress)
        assertEquals(0, result.calls)
    }
}
