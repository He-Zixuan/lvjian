// 「一句话 → 日程意图」解析器
// 与网页预览 web-preview/preview-v7.js 的 parseTaskIntent 口径保持一致（改一边务必改另一边）。
//
// 分工原则：**本文件只负责"听懂"**（日期 / 时段 / 时长 / 起止时刻 / 标题）；
//          "算空档、写数据库" 交给 TaskScheduler 与 MainViewModel。
// 为什么不用模型干这一步：模型算时间容易错（两块是否重叠、下午还剩多少），
//          而且**没配 API 时这条链路必须照样能用** —— 所以本地规则是主力。
//          将来接上模型，让模型输出同样的 TaskIntent，后面的逻辑可以原样复用。
//
// 触发门槛（不满足就当闲聊，不要硬排）：
//   ① 说了时长（"两个小时"）或起止时刻（"2点到4点"）或单个时刻（"下午三点开会"）
//   ② 剥掉时间词后还剩 ≥2 字的标题
package com.lvjian.app.util

import java.time.LocalDate

object TaskIntentParser {

    /** 一天的可排范围，与计划页可视范围（08:00~22:00）一致 */
    val DAY_SPAN_START = 8 * 60
    val DAY_SPAN_END = 22 * 60

    /** 模糊时段（上午 8–12 / 中午 12–13 / 下午 13–18 / 傍晚 17–19 / 晚上 18–22） */
    data class Period(val label: String, val start: Int, val end: Int)

    /**
     * 解析结果。range / point / period 三者最多一个非空，优先级 range > point > period：
     *  · range  —— 「几点到几点」，照说的来
     *  · point  —— 「几点开始」，从那一刻起排 durationMin 分钟
     *  · period —— 只有模糊时段（下午），在该时段里找空档
     */
    data class TaskIntent(
        val title: String,
        val dayOfWeek: Int,             // 0=周一 … 6=周日
        val weekOffset: Int,            // 相对本周的偏移（0=本周，1=下周）
        val durationMin: Int,           // 时长；用户没说时按 60 兜底
        val durationExplicit: Boolean,  // 用户是否明说了时长（用于汇报"按 1 小时排的"）
        val range: Pair<Int, Int>?,
        val point: Int?,
        val period: Period?
    )

    /** 模糊时段表。注意「傍晚」不含"晚上"、「下午」不含"中午"，所以先后顺序不影响正确性 */
    private val PERIODS = listOf(
        Regex("早上|早晨|上午|早起") to Period("上午", 8 * 60, 12 * 60),
        Regex("中午|午间|正午") to Period("中午", 12 * 60, 13 * 60),
        Regex("下午|午后") to Period("下午", 13 * 60, 18 * 60),
        Regex("傍晚") to Period("傍晚", 17 * 60, 19 * 60),
        Regex("晚上|夜里|晚间|今晚") to Period("晚上", 18 * 60, 22 * 60)
    )

    /** 中文数字表（覆盖日常说法：零~十的组合） */
    private val CN_NUM = mapOf(
        '零' to 0, '一' to 1, '两' to 2, '俩' to 2, '二' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9, '十' to 10
    )

    /** 数字的两种写法，供时长/时刻正则共用 */
    private const val N = "(?:[0-9]+(?:\\.[0-9]+)?|[一二两三四五六七八十]+|俩)"

    /** 单个时刻的写法：15:30 / 3点 / 三点半 / 三点一刻 / 7点20 */
    private const val CLOCK =
        "(?:[0-9]{1,2}\\s*[:：]\\s*[0-9]{1,2}|(?:[0-9]{1,2}|[一二两三四五六七八九十]+)\\s*[点時时](?:半|一刻|[0-9]{1,2}|[一二两三四五六七八九十]+)?)"

    /** 中文/阿拉伯数字串 → 数值；"十二"→12、"二十三"→23、"两"→2、"15"→15；解析不出返回 null */
    fun cnNum(s: String?): Int? {
        val t = s?.trim().orEmpty()
        if (t.isEmpty()) return null
        t.toIntOrNull()?.let { return it }
        if (!t.contains('十')) {
            var out = 0
            for (c in t) {
                val v = CN_NUM[c] ?: return null
                out = out * 10 + v
            }
            return out
        }
        val parts = t.split('十')
        val tens = if (parts[0].isEmpty()) 1 else (CN_NUM[parts[0].firstOrNull()] ?: return null)
        val ones = if (parts.size < 2 || parts[1].isEmpty()) 0 else (CN_NUM[parts[1].firstOrNull()] ?: return null)
        return tens * 10 + ones
    }

    /**
     * 时长词 → 分钟数。识别不出返回 null。
     * 覆盖：两个小时 / 俩小时 / 一个半小时 / 半小时 / 1.5小时 / 90分钟 / 1小时20分钟
     * 注意：**只认「分钟」，不认单个「分」** —— 否则"三点十分"会被当成 10 分钟时长。
     */
    fun parseDuration(text: String): Int? {
        // 一个半小时 / 两个半钟头（必须排在"半小时"前面，否则只吃到后半截）
        Regex("($N)\\s*个?\\s*半\\s*(?:小时|钟头)").find(text)?.let { m ->
            val n = cnNum(m.groupValues[1])
            if (n != null) return n * 60 + 30
        }
        // 半小时 / 半个钟头（不带数字的"半"）—— 只能放在上面那条之后：
        // 否则 "一个半小时" 里的 "半小时" 子串会被先匹配成 30 分钟
        if (Regex("半\\s*个?\\s*(?:小时|钟头)").containsMatchIn(text)) return 30
        Regex("($N)\\s*个?\\s*(?:小时|钟头)\\s*($N)\\s*分钟").find(text)?.let { m ->
            val h = cnNum(m.groupValues[1])
            val mm = cnNum(m.groupValues[2])
            if (h != null && mm != null) return h * 60 + mm
        }
        // 1.5小时 / 0.5小时（纯小数小时）—— 必须排在 "X小时Y分钟" 之后，
        // 否则 "1小时20分钟" 会被这里的 "1小时" 先截走，变成 60 分钟
        Regex("([0-9]+\\.[0-9]+)\\s*个?\\s*(?:小时|钟头)").find(text)?.let { m ->
            val h = m.groupValues[1].toDoubleOrNull()
            if (h != null) return Math.round(h * 60).toInt()
        }
        Regex("($N)\\s*个?\\s*(?:小时|钟头)").find(text)?.let { m ->
            val h = cnNum(m.groupValues[1])
            if (h != null) return h * 60
        }
        Regex("($N)\\s*分钟").find(text)?.let { m ->
            return cnNum(m.groupValues[1])
        }
        return null
    }

    /**
     * 单个时刻 → 当天分钟数；解析不出返回 null。
     * @param periodLabel 同一句里的时段名，用来补 12 小时（"下午3点"=15:00）
     */
    fun parseClock(s: String?, periodLabel: String?): Int? {
        if (s.isNullOrBlank()) return null
        var hour: Int?
        var min = 0
        val colon = Regex("([0-9]{1,2})\\s*[:：]\\s*([0-9]{1,2})").find(s)
        if (colon != null) {
            hour = colon.groupValues[1].toIntOrNull()
            min = colon.groupValues[2].toIntOrNull() ?: 0
        } else {
            val m = Regex("([0-9]{1,2}|[一二两三四五六七八九十]+)\\s*[点時时]\\s*(半|一刻|[0-9]{1,2}|[一二两三四五六七八九十]+)?").find(s)
                ?: return null
            hour = cnNum(m.groupValues[1]) ?: return null
            min = when {
                m.groupValues[2] == "半" -> 30
                m.groupValues[2] == "一刻" -> 15
                m.groupValues[2].isNotEmpty() -> cnNum(m.groupValues[2]) ?: 0
                else -> 0
            }
        }
        val h = hour ?: return null
        if (h > 24 || min > 59) return null
        // 下午/傍晚/晚上 说 1~11 点 → 补 12 小时；"上午11点"不动
        val adjusted = if ((periodLabel == "下午" || periodLabel == "傍晚" || periodLabel == "晚上") && h in 1..11) h + 12 else h
        return adjusted * 60 + min
    }

    /** 起止时刻区间："2点到4点" / "14:00-16:00" / "下午两点半到四点" */
    fun parseRange(text: String, periodLabel: String?): Pair<Int, Int>? {
        val m = Regex("($CLOCK)\\s*(?:到|至|-|–|—|~|～)\\s*($CLOCK)").find(text) ?: return null
        val start = parseClock(m.groupValues[1], periodLabel) ?: return null
        val end = parseClock(m.groupValues[2], periodLabel) ?: return null
        if (end <= start) return null
        return start to end
    }

    /** 句子里单独出现的一个时刻（没有"到"）："周三下午三点开会" 里的"三点" */
    fun parsePoint(text: String, periodLabel: String?): Int? {
        val m = Regex(CLOCK).find(text) ?: return null
        return parseClock(m.value, periodLabel)
    }

    /** 模糊时段；没有返回 null */
    fun parsePeriod(text: String): Period? = PERIODS.firstOrNull { it.first.containsMatchIn(text) }?.second

    /**
     * 日期词 → (dayOfWeek, weekOffset)。
     * 对话语境**默认今天**（注意：ScheduleParser 的 planGuess 默认"明天"，那是给通知预填用的）。
     * 只说"周三"而今天已过周三 → 理解为下周三。
     */
    fun parseDate(text: String, today: LocalDate): Pair<Int, Int> {
        val todayDow = today.dayOfWeek.value - 1   // 0=周一
        var abs: Int? = null
        if (Regex("大后天").containsMatchIn(text)) abs = todayDow + 3
        else if (Regex("后天").containsMatchIn(text)) abs = todayDow + 2
        else if (Regex("明天|明早|明晚|明儿").containsMatchIn(text)) abs = todayDow + 1
        else if (Regex("今天|今日|今儿|今晚").containsMatchIn(text)) abs = todayDow
        if (abs == null) {
            val m = Regex("(下周|下个礼拜|本周|这周|这个礼拜)?\\s*(?:周|星期|礼拜)([一二三四五六日天])").find(text)
            if (m != null) {
                val target = mapOf(
                    '一' to 0, '二' to 1, '三' to 2, '四' to 3, '五' to 4, '六' to 5, '日' to 6, '天' to 6
                )[m.groupValues[2].firstOrNull()] ?: todayDow
                var diff = target - todayDow
                val prefix = m.groupValues[1]
                if (prefix == "下周" || prefix == "下个礼拜") diff += 7
                else if (prefix.isEmpty() && diff < 0) diff += 7
                abs = todayDow + diff
            }
        }
        if (abs == null) abs = todayDow
        return (abs % 7 + 7) % 7 to (abs / 7)
    }

    /**
     * 从原句里剥掉日期/时段/时长/时刻/虚词，剩下的当标题。
     * 例：「今天下午打两个小时的羽毛球」→ 打羽毛球
     * 副作用（可接受）：中文"的"是定语标记，中间位置的会被删，"我的英语作业"→"我英语作业"。
     */
    fun extractTitle(text: String): String {
        val cjk = "[\\u4e00-\\u9fa5]"
        var t = " $text "
        // 「在 + 时间词」这个介词框架要先摘掉：时间词会被删掉，留下孤零零的「在」
        // （"我要在今天下午排一个小时背单词" 若不处理，最后剩下 "我要在排背单词"）。
        // 只按「在」后面紧跟时间表达来删，**不做**句首无差别删除 —— 那会毁掉"在家写作业"这类标题。
        t = t.replace(Regex("在(?=(?:大后天|后天|明天|明早|明晚|明儿|今天|今日|今儿|今晚|下周|下个礼拜|本周|这周|这个礼拜|[周星期礼拜][一二三四五六日天]|早上|早晨|上午|早起|中午|午间|正午|下午|午后|傍晚|晚上|夜里|晚间))"), "")
        t = t.replace(Regex("大后天|后天|明天|明早|明晚|明儿|今天|今日|今儿|今晚"), "")
        t = t.replace(Regex("(下周|下个礼拜|本周|这周|这个礼拜|周|星期|礼拜)[一二三四五六日天]"), "")
        t = t.replace(Regex("[0-9]{1,2}\\s*月\\s*[0-9]{1,2}\\s*[日号]"), "")
        t = t.replace(Regex("早上|早晨|上午|早起|中午|午间|正午|下午|午后|傍晚|晚上|夜里|晚间"), "")
        // 时长：先长后短，避免"一个半小时"被"半小时"截胡
        t = t.replace(Regex("($N)\\s*个?\\s*半\\s*(?:小时|钟头)"), "")
        // 裸「半」（前面没有数字，如"打半小时球"）—— 只能排在上面那条之后，
        // 否则"一个半小时"里的"半小时"子串会被先吃掉。同 parseDuration 的理由。
        t = t.replace(Regex("半\\s*个?\\s*(?:小时|钟头)"), "")
        t = t.replace(Regex("($N)\\s*个?\\s*(?:小时|钟头)\\s*($N)\\s*分钟"), "")
        t = t.replace(Regex("($N)\\s*个?\\s*(?:小时|钟头)"), "")
        t = t.replace(Regex("($N)\\s*分钟"), "")
        // 时刻：先删"起止区间"，再删零散时刻（顺序反了会把区间拆成两半）
        t = t.replace(Regex("($CLOCK)\\s*(?:到|至|-|–|—|~|～)\\s*($CLOCK)"), "")
        t = t.replace(Regex(CLOCK), "")
        // ⚠️ 必须容下开头那个空格：本函数把 t 包成了 " " + text + " "，
        // 所以原先的 ^(帮我|请|…) 永远匹配不到 —— 这正是"记一下面试"这类前缀删不掉、
        // 被塞进标题里的根因。用 + 重复是为了吃掉"我要 + 排"这种连着两个前缀词的情况。
        // 代价：以这些字开头、本身成词的标题会被误伤（"排练节目"→"练节目"），这是词表法的固有取舍。
        t = t.replace(Regex("^\\s*(?:帮我|请|麻烦|我想|我要|想要|要|去|安排|计划|排|规划|建|记一下|记一笔|记个)+"), "")
        t = t.replace(Regex("[，,。.、；;：:！!？?]+"), " ")
        t = t.replace(Regex("(?<=$cjk)的(?=$cjk)"), "")        // "打的羽毛球" → "打羽毛球"
        t = t.replace(Regex("(?<=$cjk)\\s+(?=$cjk)"), "")      // 删词后留下的空档："打 羽毛球" → "打羽毛球"
        t = t.replace(Regex("^(的|一下|一个|个|把)+"), "")
        t = t.replace(Regex("(的|吧|呢|啊|哦|呀)+" + "$"), "")  // 末尾语气词（"$" 需转义以免被当模板）
        return t.trim()
    }

    /**
     * 主入口：是不是"排期任务"？不是返回 null（交给聊天），避免"今天好累"被排进计划。
     * ⚠️「周X + 起止时刻」是课程表句式，这里主动让路（返回 null），
     *   交给 ScheduleParser 排成每周重复；只说"周三下午三点开会"的仍按一次性处理。
     */
    fun parse(text: String, today: LocalDate): TaskIntent? {
        val dur = parseDuration(text)
        val period = parsePeriod(text)
        val periodLabel = period?.label
        val range = parseRange(text, periodLabel)
        val point = if (range != null) null else parsePoint(text, periodLabel)
        if (dur == null && range == null && point == null) return null
        // 课程表句式让路（带"今天/明天"的除外：那时用户说的是临时安排）
        val hasWeekWord = Regex("(?:周|星期|礼拜)[一二三四五六日天]").containsMatchIn(text)
        val hasRelDay = Regex("今天|明天|后天|今儿|明儿").containsMatchIn(text)
        if (range != null && hasWeekWord && !hasRelDay) return null
        val title = extractTitle(text)
        if (title.length < 2) return null
        val (dow, weekOffset) = parseDate(text, today)
        val hasClock = range != null || point != null
        return TaskIntent(
            title = title,
            dayOfWeek = dow,
            weekOffset = weekOffset,
            durationMin = dur ?: (range?.let { it.second - it.first } ?: 60),
            durationExplicit = dur != null,
            range = range,
            point = point,
            period = if (hasClock) null else period
        )
    }

    /** 相对今天的说法，用于汇报里念出来（今天 / 明天 / 后天 / 周三 / 下周三） */
    fun dayLabel(dayOfWeek: Int, weekOffset: Int, today: LocalDate): String {
        val diff = weekOffset * 7 + dayOfWeek - (today.dayOfWeek.value - 1)
        val names = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
        return when (diff) {
            0 -> "今天"
            1 -> "明天"
            2 -> "后天"
            else -> (if (weekOffset > 0) "下周" else "") + names[dayOfWeek]
        }
    }

    /** 分钟数 → 口语时长（120 → "2 小时"；90 → "1 小时 30 分钟"） */
    fun fmtDur(min: Int): String {
        val h = min / 60
        val m = min % 60
        return when {
            h == 0 -> "$m 分钟"
            m == 0 -> "$h 小时"
            else -> "$h 小时 $m 分钟"
        }
    }

    /** 分钟数 → "HH:MM" */
    fun fmtMin(min: Int): String = "%02d:%02d".format(min / 60, min % 60)
}
