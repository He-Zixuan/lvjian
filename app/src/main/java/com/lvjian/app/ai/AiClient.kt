// AI 客户端：OpenAI 兼容协议
//  - shouldFilter：语义过滤判定（/chat/completions）
//  - chat：蛋黄对话（同协议，带多轮历史）
//  - listModels：GET /models（设置页「获取模型列表」）
// 原则：失败抛异常，由调用方兜底（过滤失败保留条目；对话失败给提示气泡）
package com.lvjian.app.ai

import com.lvjian.app.data.AiConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class AiClient(private val appContext: android.content.Context? = null) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * 让模型判定一条消息是否应被过滤。
     * @param rulePrompt 由 RuleEngine.buildPrompt 组装的完整提示
     * @return true=过滤；false=保留；抛异常表示调用失败（网络/额度/解析问题）
     */
    suspend fun shouldFilter(config: AiConfig, rulePrompt: String): Boolean = withContext(Dispatchers.IO) {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put(
                    "content",
                    "你是群消息过滤器。根据用户给的过滤规则判断这条消息是否应被过滤掉。" +
                        "只输出 JSON：{\"filter\":true} 或 {\"filter\":false}，不要输出任何其他内容。"
                )
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", rulePrompt)
            })
        }
        val content = chatCompletions(config, messages)
        parseVerdict(content)
    }

    /**
     * 蛋黄对话：把多轮历史发给模型，取回复文本。
     * @param history (role, content) 列表，role ∈ "user"/"assistant"
     *
     * system 提示 = 角色设定 + **整份产品说明书**（`ProductManual.TEXT`，由
     * docs/product-manual.md 生成）。这样用户问「通知访问在哪开」「为什么要设接管」
     * 它才答得出来，而不是拿通用助手的话术泛泛而谈。
     * 实测代价：说明书约 1530 token（cl100k 分词器），见 docs/product-manual.md 头部说明。
     */
    /** 一条对话。图片和文字在同一次发送里，不能拆成空文字再丢掉。 */
    data class ChatTurn(val role: String, val text: String, val imageUris: List<String> = emptyList())

    data class ChatAction(
        val kind: String,
        val title: String,
        val date: String,
        val start: String,
        val end: String,
        val place: String,
        /** 删除动作：计划表里的编号；0 表示让本地按 title/date 找 */
        val id: Long = 0
    )

    /**
     * @param plans 当前计划表（编号/日期/起止/事项），随请求一起给模型，
     *              否则它没法知道用户说的「删掉高数」对应哪一条。
     */
    suspend fun chat(
        config: AiConfig,
        history: List<ChatTurn>,
        plans: List<com.lvjian.app.data.PlanEventEntity> = emptyList()
    ): Pair<String, List<ChatAction>> =
        withContext(Dispatchers.IO) {
            val today = java.time.LocalDate.now()
            val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put(
                        "content",
                        "你是「蛋黄」，滤见 APP 里的 AI 助手。你能把事情写进计划表，也能从计划表里删掉、加进待办清单；要改时间时先删旧条目再写新条目。不要说自己写不进去或删不掉。\n" +
                            "今天是 $today，星期 ${today.dayOfWeek.value}（1 是周一）。本周一是 $monday。\n" +
                            "用户说本周是第 N 周时，第 N 周就是 $monday 这一周，后面每周加 7 天。不要再问今天几号。\n" +
                            "用户要排课、记事、加入清单时，只输出一个 JSON，不要其他文字：\n" +
                            "{\"say\":\"给用户的短回复\",\"plan\":[{\"title\":\"事项\",\"date\":\"yyyy-MM-dd\",\"start\":\"HH:mm\",\"end\":\"HH:mm\",\"place\":\"地点，没有就空\"}],\"delete\":[],\"inbox\":[\"待办原文\"]}。\n" +
                            "用户要删计划（删掉/删除/取消/撤销/不要了/写错了）时，只删他明确指出的那几条：delete 里填 [{\"id\":计划表里的编号,\"title\":\"事项\",\"date\":\"yyyy-MM-dd\"}]，优先用 id；id 没把握就填 0，用 title+date 指定。\n" +
                            "删掉的事项不要再写回 plan；用户说「删掉刚才那条/上一条」就删计划表里编号最大、时间最新的那条。一次可以删多条，全部要删就每条都列出来。\n" +
                            "plan 里每一节课、每一周都单独一条，date 用算好的自然日。只是聊天、不需要写入或删除时，plan、delete、inbox 都给空数组。\n\n" +
                            "当前计划表（编号 | 日期 起-止 | 事项 | 地点）：\n" +
                            planBlock(plans, today) + "\n\n" +
                            "下面是滤见的产品说明。用户问某个功能怎么用、在哪儿打开、为什么要这么设，一律以它为准：\n\n" +
                            ProductManual.TEXT
                    )
                })
                for (turn in history.takeLast(8)) {
                    put(JSONObject().apply {
                        put("role", turn.role)
                        put("content", contentOf(turn))
                    })
                }
            }
            parseChat(chatCompletions(config, messages))
        }

    /** 计划表 → 给模型看的紧凑清单；只列今天及以后，避免旧计划挤爆上下文 */
    private fun planBlock(plans: List<com.lvjian.app.data.PlanEventEntity>, today: java.time.LocalDate): String {
        val upcoming = plans
            .filter { it.epochDay >= today.toEpochDay() }
            .sortedWith(compareBy({ it.epochDay }, { it.startMin }))
            .take(80)
        if (upcoming.isEmpty()) return "（空）"
        val dayNames = "一二三四五六日"
        return upcoming.joinToString("\n") { e ->
            val d = java.time.LocalDate.ofEpochDay(e.epochDay)
            val place = if (e.place.isBlank()) "" else " | ${e.place}"
            val repeat = if (e.repeatWeekly) "（每周重复）" else ""
            "编号 ${e.id} | $d 周${dayNames[d.dayOfWeek.value - 1]} ${fmt(e.startMin)}-${fmt(e.endMin)} | ${e.title}$place$repeat"
        }
    }

    private fun fmt(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

    private fun parseChat(raw: String): Pair<String, List<ChatAction>> {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return raw.trim() to emptyList()
        return try {
            val obj = JSONObject(raw.substring(start, end + 1))
            val actions = mutableListOf<ChatAction>()
            obj.optJSONArray("plan")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val it = arr.optJSONObject(i) ?: continue
                    val title = it.optString("title")
                    if (title.isBlank()) continue
                    actions += ChatAction("plan", title, it.optString("date"), it.optString("start"), it.optString("end"), it.optString("place"))
                }
            }
            obj.optJSONArray("delete")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val it = arr.optJSONObject(i) ?: continue
                    val id = it.optLong("id", 0)
                    val title = it.optString("title").trim()
                    val date = it.optString("date").trim()
                    // 只有 id 或事项名至少给了一个才算有效删除；防止模型吐空对象把计划删光
                    if (id > 0 || title.isNotBlank()) {
                        actions += ChatAction("plan_del", title, date, "", "", "", id)
                    }
                }
            }
            obj.optJSONArray("inbox")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val title = arr.optString(i)
                    if (title.isNotBlank()) actions += ChatAction("inbox", title, "", "", "", "")
                }
            }
            obj.optString("say").ifBlank { "好，我按这个处理。" } to actions
        } catch (e: Exception) {
            raw.trim() to emptyList()
        }
    }

    private fun contentOf(turn: ChatTurn): Any {
        if (turn.imageUris.isEmpty()) return turn.text
        val parts = JSONArray()
        if (turn.text.isNotBlank()) {
            parts.put(JSONObject().put("type", "text").put("text", turn.text))
        }
        turn.imageUris.take(4).forEach { uri ->
            val data = imageDataUrl(uri) ?: return@forEach
            parts.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", data)))
        }
        return if (parts.length() == 0) turn.text.ifBlank { "（图片没能读出来）" } else parts
    }

    private fun imageDataUrl(uri: String): String? = try {
        val ctx = appContext ?: return null
        val parsed = android.net.Uri.parse(uri)
        val raw = ctx.contentResolver.openInputStream(parsed)?.use { it.readBytes() } ?: return null
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return null
        val maxEdge = 1280
        val scale = minOf(1f, maxEdge.toFloat() / maxOf(bitmap.width, bitmap.height))
        val scaled = if (scale < 1f) {
            android.graphics.Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else bitmap
        val out = java.io.ByteArrayOutputStream()
        scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 80, out)
        "data:image/jpeg;base64," + android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    } catch (e: Exception) {
        null
    }

    /**
     * 批量判定：一组规则 + 多条编号消息，一次调用全判完。
     * 判定和摘要同一次调用产出，所以批量整理后的清单也有摘要，不再回落成原文首行。
     * @param rulesBlock 规则清单文本（由 BatchAiFilter 拼装）
     * @param messagesBlock 编号消息清单文本（[i] 群 | 发送人：正文）
     * @return i → 判定；解析失败的编号不在 map 里，由调用方兜底
     */
    suspend fun judgeBatch(config: AiConfig, rulesBlock: String, messagesBlock: String): Map<Int, BatchVerdict> =
        withContext(Dispatchers.IO) {
            val messages = JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", batchSystemPrompt())
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "用户的整理规则：\n$rulesBlock\n消息列表：\n$messagesBlock")
                })
            }
            val content = chatCompletions(config, messages)
            parseBatchVerdicts(content)
        }

    /** 批量判定的 system 提示：判定准则 + 输出格式 + 摘要要求 */
    private fun batchSystemPrompt(): String = buildString {
        append("你是滤见的消息整理助手。按下面的判定准则和用户的整理规则，")
        append("逐条判断每条消息应该丢弃还是保留，并给保留的消息写一句话摘要。\n\n")
        append(com.lvjian.app.rules.TriageRules.FALLBACK).append("\n\n")
        append("输出要求：每条消息都要给结论，不许漏。只输出 JSON 数组，不要任何其他内容：")
        append("""[{"i":1,"filter":true,"rule":"命中的规则名或null","summary":""},""")
        append("""{"i":2,"filter":false,"rule":null,"summary":"一句话摘要"}]""")
        append("\ni 是消息编号；filter=true 表示丢弃；rule 只在确实命中某条规则时填规则名，否则 null；")
        append("summary 只给保留的消息写：说清是什么事、要做什么、什么时候，30 字内；丢弃的消息 summary 给空字符串。")
    }

    /**
     * 一条消息一次调用：按规则判定，同时写摘要和加入计划预填。
     * 过滤失败时调用方保留消息；摘要失败时列表继续显示原文。
     * 判定准则来自 TriageRules.PATTERN，与批量整理共用同一份，别在这里另写一套。
     */
    suspend fun judgeAndBrief(
        config: AiConfig,
        rulesBlock: String,
        messageBlock: String,
        today: String
    ): MessageAiResult = withContext(Dispatchers.IO) {
        val messages = JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put(
                    "content",
                    "你是滤见的消息助手。按下面的判定准则和用户规则看这条消息，" +
                        "给出丢弃还是保留的结论、一句话摘要和加入计划的预填。今天是 $today。\n\n" +
                        com.lvjian.app.rules.TriageRules.FALLBACK + "\n\n" +
                        "只输出一个 JSON 对象，不要输出其他内容：" +
                        "{\"filter\":true或false,\"rule\":\"命中的规则名或null\"," +
                        "\"summary\":\"一句话，不超过30字\"," +
                        "\"title\":\"若适合放进日程就写事项名，否则空字符串\"," +
                        "\"place\":\"地点，没有就空字符串\"," +
                        "\"date\":\"yyyy-MM-dd，没有明确日期就空字符串\"," +
                        "\"start\":\"HH:mm，没有明确时间就空字符串\"," +
                        "\"end\":\"HH:mm，没有就空字符串\"}。" +
                        "summary 给清单列表看：说清是什么事、要做什么、什么时候；filter 为 true 时可以给空字符串。" +
                        "保留规则命中时 filter 必须是 false。没有命中任何规则时 filter 是 false、rule 是 null。" +
                        "拿不准时 filter 是 false。"
                )
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", "规则：\n$rulesBlock\n消息：\n$messageBlock")
            })
        }
        parseMessageResult(chatCompletions(config, messages))
    }

    /** 批量判定的一条结论。summary 只对保留的消息有意义，丢弃的给空串。 */
    data class BatchVerdict(
        val filter: Boolean,
        val ruleName: String?,
        val summary: String
    )

    data class MessageAiResult(
        val filter: Boolean,
        val ruleName: String?,
        val summary: String,
        val title: String,
        val place: String,
        val date: String,
        val start: String,
        val end: String
    )

    /** 拉取可用模型列表：GET {baseUrl}/models，返回 id 列表；失败抛带原因的异常 */
    suspend fun listModels(config: AiConfig): List<String> = withContext(Dispatchers.IO) {
        val base = config.baseUrl.trim().trimEnd('/')
        if (base.isBlank()) throw AiException("还没填 Base URL")
        if (config.apiKey.isBlank()) throw AiException("还没填 API Key")
        if (!base.startsWith("http://") && !base.startsWith("https://")) {
            throw AiException("Base URL 要以 http:// 或 https:// 开头")
        }
        val url = base + "/models"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey.trim()}")
            .get()
            .build()
        try {
            http.newCall(request).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw AiException("HTTP ${resp.code}：${raw.take(120).ifBlank { "接口没有返回说明" }}")
                }
                if (raw.isBlank()) throw AiException("空响应")
                val body = try {
                    JSONObject(raw)
                } catch (e: Exception) {
                    throw AiException("返回的不是模型列表（${raw.take(60)}）")
                }
                val data = body.optJSONArray("data") ?: throw AiException("返回里没有 data 列表")
                buildList {
                    for (i in 0 until data.length()) {
                        val id = data.optJSONObject(i)?.optString("id").orEmpty()
                        if (id.isNotBlank()) add(id)
                    }
                }.also { if (it.isEmpty()) throw AiException("接口返回了空列表") }
            }
        } catch (e: AiException) {
            throw e
        } catch (e: Exception) {
            throw AiException(e.message ?: "网络请求失败")
        }
    }

    // ---- 内部实现 ----

    /** /chat/completions 调用：返回 choices[0].message.content */
    private fun chatCompletions(config: AiConfig, messages: JSONArray): String {
        val bodyJson = JSONObject().apply {
            put("model", config.model)
            put("messages", messages)
            // 不发送 thinking/reasoning 参数：不同模型对该字段的取值约束不同，
            // 由服务端按当前模型的默认推理策略处理，避免把 disabled 转成不支持的 none。
        }
        // baseUrl 兼容带不带尾部斜杠两种写法
        val url = config.baseUrl.trimEnd('/') + "/chat/completions"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw AiException("HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
            }
            val respText = resp.body?.string() ?: throw AiException("空响应")
            return JSONObject(respText)
                .getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
        }
    }

    /**
     * 宽松解析模型输出：优先按 JSON 读 "filter" 字段；
     * 模型偶尔包一层 ```json 代码块或夹带文字时，退化为查找 true/false 关键字
     */
    private fun parseVerdict(content: String): Boolean {
        val trimmed = content.trim()
        // 去掉可能的 markdown 代码块包裹
        val cleaned = trimmed
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        return try {
            JSONObject(cleaned).optBoolean("filter", cleaned.contains("true"))
        } catch (e: Exception) {
            // 兜底：输出不是合法 JSON 时按关键字判断
            cleaned.contains("\"filter\":true") || cleaned.contains("\"filter\": true")
        }
    }

    /**
     * 宽松解析批量输出：剥 markdown 代码块 → JSONArray → 按 "i" 建映射。
     * 模型偶尔夹带文字时，退化为截取第一个 '[' 到最后一个 ']' 之间的内容再试一次。
     * summary 缺失或超长都按空/截断处理，不影响判定结果。
     */
    private fun parseBatchVerdicts(content: String): Map<Int, BatchVerdict> {
        val cleaned = content.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        // 兜底：截取数组部分（模型在数组前后说话的情况）
        val arrayText = try {
            JSONArray(cleaned); cleaned
        } catch (e: Exception) {
            val s = cleaned.indexOf('[')
            val e2 = cleaned.lastIndexOf(']')
            if (s >= 0 && e2 > s) cleaned.substring(s, e2 + 1) else throw AiException("批量判定输出无法解析")
        }
        val arr = JSONArray(arrayText)
        val out = HashMap<Int, BatchVerdict>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val idx = o.optInt("i", -1)
            if (idx < 0) continue
            val filter = o.optBoolean("filter", false)
            val rule = o.optString("rule", "").takeIf { filter && it.isNotBlank() && it != "null" }
            // 丢弃的条目不写摘要（进已过滤不需要）；保留的取模型给的摘要
            val summary = if (filter) "" else o.optString("summary", "").trim().take(40)
            out[idx] = BatchVerdict(filter, rule, summary)
        }
        return out
    }

    /** 解析单条判定 + 摘要 + 预填。包在代码块里或前后有废话时，截取花括号再试。 */
    private fun parseMessageResult(content: String): MessageAiResult {
        val cleaned = content.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        val obj = try {
            JSONObject(cleaned)
        } catch (e: Exception) {
            val s = cleaned.indexOf('{')
            val e2 = cleaned.lastIndexOf('}')
            if (s >= 0 && e2 > s) JSONObject(cleaned.substring(s, e2 + 1))
            else throw AiException("模型没有返回可解析的结果")
        }
        val filter = obj.optBoolean("filter", false)
        val rule = obj.optString("rule", "").takeIf { filter && it.isNotBlank() && it != "null" }
        return MessageAiResult(
            filter = filter,
            ruleName = rule,
            summary = obj.optString("summary", "").trim().take(40),
            title = obj.optString("title", "").trim().take(40),
            place = obj.optString("place", "").trim().take(40),
            date = obj.optString("date", "").trim(),
            start = obj.optString("start", "").trim(),
            end = obj.optString("end", "").trim()
        )
    }

    class AiException(message: String) : Exception(message)
}
