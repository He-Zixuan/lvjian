package com.lvjian.app.ai

import com.lvjian.app.data.AiConfig
import com.lvjian.app.data.PlanEventEntity
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.RecordedRequest
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AiClientCompatibilityTest {
    @Test
    fun restrictedModelUsesProviderDefaultTemperature() = runBlocking {
        val server = MockWebServer()
        var received: JSONObject? = null
        server.dispatcher = object : Dispatcher() {
          override fun dispatch(request: RecordedRequest): MockResponse {
            val body = JSONObject(request.body.readUtf8())
            received = body
            val incompatible = body.has("temperature") && body.getDouble("temperature") != 0.6
            val response = if (incompatible) {
                """{"error":{"message":"invalid temperature: only 0.6 is allowed for this model","type":"invalid_request_error"}}"""
            } else {
                JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message",
                    JSONObject().put("content", """{"say":"连接正常","plan":[],"inbox":[]}""")))).toString()
            }
            return MockResponse().setResponseCode(if (incompatible) 400 else 200).setBody(response)
          }
        }
        server.start()
        try {
            val config = AiConfig("http://127.0.0.1:${server.port}", "test-key", "restricted-model", true)
            val result = AiClient().chat(config, listOf(AiClient.ChatTurn("user", "你好")))
            assertEquals("连接正常", result.first)
            assertTrue(result.second.isEmpty())
            assertFalse("模型默认采样参数应由服务端选择", received!!.has("temperature"))
            assertEquals("restricted-model", received!!.getString("model"))
            assertEquals("你好", received!!.getJSONArray("messages").getJSONObject(1).getString("content"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun chatSendsPlanSnapshotAndParsesDeleteActions() = runBlocking {
        val server = MockWebServer()
        var systemPrompt = ""
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = JSONObject(request.body.readUtf8())
                systemPrompt = body.getJSONArray("messages").getJSONObject(0).getString("content")
                val content = """{"say":"删掉了","plan":[],"delete":[{"id":7,"title":"高数","date":"2026-10-20"}],"inbox":[]}"""
                val response = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put(
                    "message", JSONObject().put("content", content)))).toString()
                return MockResponse().setResponseCode(200).setBody(response)
            }
        }
        server.start()
        try {
            val config = AiConfig("http://127.0.0.1:${server.port}", "test-key", "test-model", true)
            val plan = PlanEventEntity(
                id = 7, epochDay = java.time.LocalDate.of(2026, 10, 20).toEpochDay(),
                startMin = 14 * 60, endMin = 16 * 60, title = "高数", place = "教3"
            )
            val result = AiClient().chat(config, listOf(AiClient.ChatTurn("user", "删掉高数")), listOf(plan))
            assertEquals("删掉了", result.first)
            assertEquals(1, result.second.size)
            val action = result.second.single()
            assertEquals("plan_del", action.kind)
            assertEquals(7L, action.id)
            assertEquals("高数", action.title)
            assertTrue("计划表要随请求发给模型，否则它不知道删哪条", systemPrompt.contains("编号 7"))
            assertTrue(systemPrompt.contains("\"delete\""))
        } finally {
            server.shutdown()
        }
    }
}
