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
    fun chunkBoundaryCarriesContextAndCanUpdateThePreviousChunk() = runBlocking {
        val server = MockWebServer()
        val first = JSONArray()
        (1..40).forEach { first.put(JSONObject().put("i", it).put("filter", false).put("summary", "事项$it")) }
        server.enqueue(responseFor(first.toString()))
        server.enqueue(responseFor("""[{"i":1,"filter":false,"merge_into":2,"summary":"补充"},
            {"i":2,"filter":false,"summary":"问卷明天17点截止"}]"""))
        server.start()
        try {
            val engine = BatchAiFilter(AiClient(), AiConfig("http://127.0.0.1:${server.port}", "test-key", "test-model", true))
            var previous: MessageEntity? = null
            val result = engine.run((1L..41L).map { message(it, if (it == 41L) "问卷明天17点截止" else "事项$it") },
                listOf(builtinRule), { _, _ -> }, contextFor = { listOfNotNull(previous) },
                saveMerged = { root, sources, _ ->
                    assertEquals(40L, root.id)
                    assertEquals(listOf(41L), sources.map { it.id })
                    true
                }) { id, _, _, _, summary -> if (id == 40L) previous = message(id, "事项40").copy(summary = summary) }
            assertEquals(40, result.kept)
            assertEquals(1, result.merged)
            assertEquals(2, result.calls)
            val firstPrompt = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages").getJSONObject(1).getString("content")
            assertTrue(firstPrompt.contains("只读上下文"))
            assertTrue(firstPrompt.contains("问卷明天17点截止"))
        } finally { server.shutdown() }
    }

    @Test
    fun changedMergeTargetLeavesNewSourcesForRetry() = runBlocking {
        val server = MockWebServer()
        server.enqueue(responseFor("""[{"i":1,"filter":false,"summary":"问卷"},
            {"i":2,"filter":false,"summary":"补充","merge_into":1}]"""))
        server.start()
        try {
            val engine = BatchAiFilter(AiClient(), AiConfig("http://127.0.0.1:${server.port}", "test-key", "test-model", true))
            val result = engine.run(listOf(message(1, "问卷"), message(2, "明天截止")), listOf(builtinRule), { _, _ -> },
                saveMerged = { _, _, _ -> false }) { _, _, _, _, _ -> throw AssertionError("不能部分消费失败的合并组") }
            assertEquals(2, result.failed)
            assertEquals(0, result.kept)
            assertEquals(0, result.merged)
        } finally { server.shutdown() }
    }

    @Test
    fun repeatedNoticesAndDeadlineFollowupBecomeOneItemWithAllSources() = runBlocking {
        val server = MockWebServer()
        server.enqueue(responseFor("""[
            {"i":1,"filter":false,"summary":"今天17点前填培养方案问卷","title":"培养方案问卷","date":"2026-10-20","start":"17:00"},
            {"i":2,"filter":false,"merge_into":1,"summary":"重复通知"},
            {"i":3,"filter":false,"merge_into":1,"summary":"截止补充"}
        ]"""))
        server.start()
        try {
            val engine = BatchAiFilter(AiClient(), AiConfig("http://127.0.0.1:${server.port}", "test-key", "test-model", true))
            val standalone = mutableListOf<Long>()
            val saved = mutableListOf<Pair<Long, List<Long>>>()
            val progress = mutableListOf<Int>()
            val result = engine.run(listOf(message(1, "培养方案问卷请填写"),
                message(2, "转发：培养方案问卷请填写"), message(3, "上面的问卷今天17点截止")),
                listOf(builtinRule), { done, _ -> progress += done },
                saveMerged = { root, members, verdict ->
                    saved += root.id to members.map { it.id }
                    assertEquals("今天17点前填培养方案问卷", verdict.summary)
                    assertEquals("2026-10-20", verdict.date)
                    assertEquals("17:00", verdict.start)
                    true
                }
            ) { id, _, _, _, _ -> standalone += id }
            assertEquals(listOf(1L to listOf(2L, 3L)), saved)
            assertTrue(standalone.isEmpty())
            assertEquals(1, result.kept)
            assertEquals(2, result.merged)
            assertEquals(listOf(0, 1, 2, 3), progress)
        } finally { server.shutdown() }
    }

    @Test
    fun laterBatchUpdatesExistingItemAndNeverUsesAnotherConversation() = runBlocking {
        val server = MockWebServer()
        server.enqueue(responseFor("""[{"i":1,"filter":false,"merge_into":2,"summary":"催办"},
            {"i":2,"filter":false,"summary":"培养方案问卷改为明天17点截止"}]"""))
        server.start()
        try {
            val engine = BatchAiFilter(AiClient(), AiConfig("http://127.0.0.1:${server.port}", "test-key", "test-model", true))
            val result = engine.run(listOf(message(100, "上面的问卷改为明天截止")), listOf(builtinRule), { _, _ -> },
                contextFor = { listOf(message(1, "培养方案问卷今天截止"),
                    message(2, "其他群私有上下文").copy(groupName = "别的群"),
                    message(3, "另一应用私有上下文").copy(appPackage = "com.tencent.mobileqq")) },
                saveMerged = { root, members, _ ->
                    assertEquals(1L, root.id)
                    assertEquals(listOf(100L), members.map { it.id })
                    true
                }
            ) { _, _, _, _, _ -> throw AssertionError("补充应更新原项") }
            assertEquals(0, result.kept)
            assertEquals(1, result.merged)
            val prompt = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages").getJSONObject(1).getString("content")
            assertFalse(prompt.contains("其他群私有上下文"))
            assertFalse(prompt.contains("另一应用私有上下文"))
        } finally { server.shutdown() }
    }

    @Test
    fun cyclicUnknownOrMissingMergeTargetsDoNotHideMessages() = runBlocking {
        val server = MockWebServer()
        server.enqueue(responseFor("""[
            {"i":1,"filter":false,"merge_into":2,"summary":"原文1"},
            {"i":2,"filter":false,"merge_into":1,"summary":"原文2"},
            {"i":3,"filter":false,"merge_into":99,"summary":"原文3"},
            {"i":4,"filter":false,"merge_into":5,"summary":"原文4"}
        ]"""))
        server.start()
        try {
            val engine = BatchAiFilter(AiClient(), AiConfig("http://127.0.0.1:${server.port}", "test-key", "test-model", true))
            val saved = mutableListOf<Long>()
            val result = engine.run((1L..5).map { message(it, "通知$it") }, listOf(builtinRule), { _, _ -> },
                saveMerged = { _, _, _ -> throw AssertionError("非法合并不能落库") }
            ) { id, _, _, _, _ -> saved += id }
            assertEquals(listOf(1L, 2L, 3L, 4L), saved)
            assertEquals(1, result.failed)
            assertEquals(0, result.merged)
        } finally { server.shutdown() }
    }

    @Test
    fun protectedNoticesRemainKeptEvenWhenModelFiltersThem() = runBlocking {
        val server = MockWebServer()
        server.enqueue(responseFor("""[{"i":1,"filter":true,"rule":"滤掉无用信息"}]"""))
        server.start()
        try {
            val engine = BatchAiFilter(AiClient(), AiConfig("http://127.0.0.1:${server.port}", "test-key", "test-model", true))
            val result = engine.run(listOf(message(1, "@所有人：填写问卷")), listOf(builtinRule), { _, _ -> },
                mustKeep = { true }) { _, filtered, _, _, _ -> assertFalse(filtered) }
            assertEquals(1, result.kept)
        } finally { server.shutdown() }
    }

    @Test
    fun eachConversationGetsItsOwnChronologicalBatch() = runBlocking {
        val server = MockWebServer()
        val prompts = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val prompt = JSONObject(request.body.readUtf8()).getJSONArray("messages")
                    .getJSONObject(1).getString("content")
                prompts += prompt
                return responseFor("""[{"i":1,"filter":false,"summary":"通知"},{"i":2,"filter":false,"summary":"补充"}]""")
            }
        }
        server.start()
        try {
            val engine = BatchAiFilter(AiClient(), AiConfig("http://127.0.0.1:${server.port}", "test-key", "test-model", true))
            engine.run(listOf(
                message(3, "问卷今天17点截止"),
                message(2, "另一群的奖学金通知").copy(groupName = "年级群"),
                message(1, "请填写培养方案问卷")
            ), listOf(builtinRule), { _, _ -> }) { _, _, _, _, _ -> }
            assertEquals("同规则的不同群也必须分别处理，避免上下文串群", 2, prompts.size)
            val course = prompts.single { it.contains("请填写培养方案问卷") }
            assertFalse(course.contains("另一群的奖学金通知"))
            assertTrue("同群消息应按时间排序", course.indexOf("请填写培养方案问卷") < course.indexOf("问卷今天17点截止"))
        } finally {
            server.shutdown()
        }
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
