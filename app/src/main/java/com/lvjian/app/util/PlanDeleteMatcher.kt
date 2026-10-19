package com.lvjian.app.util

import com.lvjian.app.data.PlanEventEntity
import java.time.LocalDate

/**
 * 计划删除/改期的目标识别。
 *
 * 背景：蛋黄（AI 对话）写进计划的条目以前没有"最近一次"指针，用户说「删掉」时
 * 本地链路找不到目标，模型也没有删除能力，于是只能写、不能删。
 * 这里把「删哪条」的判断集中成纯函数：本地「删掉高数」和模型返回的 delete 数组
 * 共用同一套匹配规则，避免两边各理解一套。
 */
object PlanDeleteMatcher {

    /** id 优先；没有 id 时用 title，必要时加 date；all=清空 */
    data class Request(
        val id: Long = 0,
        val title: String = "",
        val date: String = "",
        val all: Boolean = false
    )

    private val DELETE_VERB = Regex("删掉|删除|删了|移除|去掉|撤销|作废|取消掉|取消|不要了|不用了|清空|全删")
    private val PLAN_WORD = Regex("计划|日程|安排|课表|课程|事项")
    private val SETTINGS_WORD = Regex("应用|通知|收集|规则|接管|权限|设置|监听")
    private val ALL_WORD = Regex("所有|全部|全都|都删|全删|清空")
    private val GENERIC = setOf("计划", "日程", "安排", "事项", "东西", "内容", "课", "课程")

    fun normalize(s: String): String = s.trim()
        .trim('「', '」', '『', '』', '“', '”', '"', '\'', '《', '》', '(', ')', '（', '）')
        .replace(Regex("\\s+"), "")
        .lowercase()

    /**
     * 对话原句 → 删除请求；不是删除句返回 null。
     * 只认"删计划"的语境：有事项名、有日期，或者明说"计划/日程/课"。
     * 这样「取消所有应用」这类设置指令不会被误当成"清空计划"。
     */
    fun parseLocal(text: String, today: LocalDate): Request? {
        val t = text.trim()
        if (t.isEmpty() || !DELETE_VERB.containsMatchIn(t)) return null
        val title = extractTitle(t)
        val date = parseDate(t, today)
        // 「取消所有应用 / 关闭通知」是设置指令，不能顺手删掉名字里带"应用"的计划
        if (title.isNotBlank() && SETTINGS_WORD.containsMatchIn(t) && !PLAN_WORD.containsMatchIn(t)) return null
        val all = ALL_WORD.containsMatchIn(t) && (PLAN_WORD.containsMatchIn(t) || Regex("都删|全删|清空").containsMatchIn(t))
        if (title.isBlank() && date == null && !all && !isBareDelete(t)) return null
        return Request(title = title, date = date?.toString().orEmpty(), all = all)
    }

    /** 「删掉」「不要了」「删掉刚才写的计划」这类没有具体事项名的指代句，按"最近一条"处理 */
    private fun isBareDelete(t: String): Boolean {
        val rest = t.replace(DELETE_VERB, "")
            .replace(Regex("帮我|请|麻烦|把|给|就|那|这个|那个|刚才|刚刚|上面|之前|上次|写的|写错|错的|计划|日程|安排|事项|那条|这条|它|吧|呢|了|啊|哦|呀|一下|[\\s，,。.、；;：:！!？?]"), "")
        return rest.isEmpty()
    }

    /** 「改到晚上」前若有「把高数…」，取出来当目标；没有返回 null */
    fun parseEditTarget(text: String, today: LocalDate): Request? {
        val t = text.trim()
        if (!Regex("改|挪|换|移|调|推|推迟|提前|延长|缩短").containsMatchIn(t)) return null
        val raw = Regex("把(.+?)(?:改|挪|换|移|调|推|推迟|提前)").find(t)?.groupValues?.get(1)
            ?: Regex("(?:改|挪|换|移|调|推|推迟|提前)(?:一下|下|动)?(.+?)(?:到|成|为)").find(t)?.groupValues?.get(1)
            ?: return null
        val title = extractTitle(raw)
        if (title.isBlank()) return null
        return Request(title = title, date = parseDate(t, today)?.toString().orEmpty())
    }

    /**
     * 在现有计划里找要删的目标。
     * - id 命中直接返回；
     * - 指定日期则先按日期筛（每周重复的按星期几算同一天）；
     * - 指定事项名则按标题匹配（支持「高数」命中「高等数学」）；
     * - 什么都没指定就用 [fallback]（最近写入的那条），再退到最近新建的一条。
     */
    fun match(
        events: List<PlanEventEntity>,
        request: Request,
        today: LocalDate,
        fallback: PlanEventEntity? = null
    ): List<PlanEventEntity> {
        if (request.id > 0) {
            // 模型给了编号就只认编号；找不到说明它记错了，绝不退化成"删最新一条"
            return events.firstOrNull { it.id == request.id }?.let { listOf(it) } ?: emptyList()
        }
        val day = parseIsoDate(request.date)
        var candidates = events.filter { e ->
            day == null || e.epochDay == day.toEpochDay() ||
                (e.repeatWeekly && LocalDate.ofEpochDay(e.epochDay).dayOfWeek == day.dayOfWeek)
        }
        // 没指定日期时，优先今天及以后：用户说「删掉高数」通常指还没上的那节
        if (day == null) {
            val upcoming = candidates.filter { it.epochDay >= today.toEpochDay() }
            if (upcoming.isNotEmpty()) candidates = upcoming
        }
        val query = normalize(request.title)
        val byTitle = if (query.isBlank()) emptyList() else candidates.filter { titleMatches(query, normalize(it.title)) }

        if (request.all) return if (byTitle.isNotEmpty()) byTitle else candidates
        if (byTitle.isNotEmpty()) {
            if (byTitle.size == 1) return byTitle
            // 同名多条：优先最近新建的（刚写错的那条），日期已筛过时也符合直觉
            return listOf(byTitle.maxByOrNull { it.id }!!)
        }
        if (query.isNotBlank()) return emptyList()

        if (fallback != null) {
            candidates.firstOrNull { it.id == fallback.id }?.let { return listOf(it) }
            // 明确说了日期却不在这天，别拿别天的条目凑数
            if (day != null) return emptyList()
            return listOf(fallback)
        }
        return candidates.maxByOrNull { it.id }?.let { listOf(it) } ?: emptyList()
    }

    private fun titleMatches(query: String, title: String): Boolean {
        if (query.isBlank() || title.isBlank()) return false
        if (title.contains(query) || query.contains(title)) return true
        // 「高数」→「高等数学」：按字重合度兜底，至少两个字且八成命中
        val q = query.toSet()
        val t = title.toSet()
        val hit = q.count { it in t }
        return hit >= 2 && hit.toDouble() / q.size >= 0.8
    }

    private fun parseIsoDate(raw: String): LocalDate? =
        runCatching { LocalDate.parse(raw.trim().take(10)) }.getOrNull()

    /** 今天/明天/周X 等 → 具体日期；没说日期返回 null（不是"今天"） */
    private fun parseDate(text: String, today: LocalDate): LocalDate? {
        if (Regex("前天").containsMatchIn(text)) return today.minusDays(2)
        if (Regex("昨天|昨日").containsMatchIn(text)) return today.minusDays(1)
        if (Regex("大后天").containsMatchIn(text)) return today.plusDays(3)
        if (Regex("后天").containsMatchIn(text)) return today.plusDays(2)
        if (Regex("明天|明早|明晚|明儿").containsMatchIn(text)) return today.plusDays(1)
        if (Regex("今天|今日|今儿|今晚").containsMatchIn(text)) return today
        val m = Regex("(下周|下个礼拜|本周|这周|这个礼拜)?\\s*(?:周|星期|礼拜)([一二三四五六日天])").find(text) ?: return null
        val target = mapOf(
            '一' to 0, '二' to 1, '三' to 2, '四' to 3, '五' to 4, '六' to 5, '日' to 6, '天' to 6
        )[m.groupValues[2].firstOrNull()] ?: return null
        val todayDow = today.dayOfWeek.value - 1
        var diff = target - todayDow
        val prefix = m.groupValues[1]
        if (prefix == "下周" || prefix == "下个礼拜") diff += 7
        else if (prefix.isEmpty() && diff < 0) diff += 7
        return today.plusDays(diff.toLong())
    }

    /** 删掉动词/日期/指代词之后剩下的部分当事项名；泛词（"课""计划"）视为没指定 */
    private fun extractTitle(text: String): String {
        var t = text
        t = t.replace(Regex("^(帮我|请|麻烦|把|将|给我)+"), " ")
        t = t.replace(DELETE_VERB, " ")
        t = t.replace(Regex("昨天|昨日|前天|今天|今日|今儿|今晚|明天|明早|明晚|明儿|后天|大后天"), " ")
        t = t.replace(Regex("(下周|下个礼拜|本周|这周|这个礼拜|周|星期|礼拜)[一二三四五六日天]"), " ")
        t = t.replace(Regex("\\d{1,2}\\s*[月/]\\s*\\d{1,2}\\s*[日号]?"), " ")
        t = t.replace(Regex("所有|全部|全都|都|全删|清空"), " ")
        t = t.replace(Regex("刚刚|刚才|之前|上次|上面|那条|这条|这个|那个|里面|里的|其中|一下|一条|写错|错的|写|的"), " ")
        t = t.replace(Regex("[，,。.、；;：:！!？?“”\"'「」『』《》\\s]+"), " ")
        t = t.replace(Regex("(计划|日程|安排|事项|课程|课|它|掉|吧|呢|了|啊|哦|呀)+$"), "").trim()
        return if (t.length >= 2 && t !in GENERIC) t else ""
    }
}