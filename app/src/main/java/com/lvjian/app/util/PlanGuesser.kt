// 「加入计划」的本地预填推断（移植自 preview.js planGuessFromMessage / guessPlace / guessTitle）
// 规则推断：相对日（今天/明天/后天）→ 周几 → 具体日期；时间取文中第一个 HH:MM；地点取"地点改到 X / 在 X 楼馆厅室"
// AI 配置齐全时由模型预填的效果更好，这里是本地兜底
package com.lvjian.app.util

import java.time.LocalDate
import java.time.temporal.ChronoField

object PlanGuesser {

    data class Guess(
        val epochDay: Long,   // 绝对日期
        val startMin: Int,
        val endMin: Int,
        val place: String
    )

    /** 从消息文本推断日期/时间/地点 */
    fun guess(text: String): Guess {
        val today = LocalDate.now()
        var date: LocalDate? = null

        // 相对日
        if (date == null && text.contains("后天")) date = today.plusDays(2)
        if (date == null && (text.contains("明天") || text.contains("明早"))) date = today.plusDays(1)
        if (date == null && (text.contains("今天") || text.contains("今日"))) date = today

        // 周几：「本周五」「周三」「星期日」
        if (date == null) {
            val m = Regex("""[周星]期?([一二三四五六日天])""").find(text)
            if (m != null) {
                val target = mapOf(
                    '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '日' to 7, '天' to 7
                )[m.groupValues[1][0]]!!
                // 下一个该周几（含本周往后）
                var d = today.with(ChronoField.DAY_OF_WEEK, target.toLong())
                if (d.isBefore(today)) d = d.plusWeeks(1)
                date = d
            }
        }

        // 具体日期：「10月23日」
        if (date == null) {
            val m = Regex("""(\d{1,2})\s*月\s*(\d{1,2})\s*[日号]""").find(text)
            if (m != null) {
                var d = LocalDate.of(today.year, m.groupValues[1].toInt(), m.groupValues[2].toInt())
                if (d.isBefore(today)) d = d.plusYears(1) // 今年的已过去 → 明年
                date = d
            }
        }

        if (date == null) date = today.plusDays(1) // 什么都没提：默认明天

        // 时间：文中第一个 HH:MM；没有则默认 09:00，时长 1 小时
        var start = 9 * 60
        val tm = Regex("""(\d{1,2})\s*[:：]\s*(\d{2})""").find(text)
        if (tm != null) start = tm.groupValues[1].toInt() * 60 + tm.groupValues[2].toInt()
        start = (start / 5) * 5 // 对齐 5 分钟
        start = start.coerceIn(0, 23 * 60 + 55)

        return Guess(date.toEpochDay(), start, minOf(start + 60, 24 * 60), guessPlace(text))
    }

    /** 地点抽取：优先「地点改到 X」，其次「在/到 X（含楼馆厅室中心）」 */
    fun guessPlace(text: String): String {
        var m = Regex("""地点(?:改到|为|是)?[：:，,]?\s*([^\s，。；;]+)""").find(text)
        if (m != null) return m.groupValues[1].trim()
        m = Regex("""[在到]([^\s，。；;]*(?:楼|馆|厅|室|中心)[^\s，。；;]*)""").find(text)
        return m?.groupValues?.get(1)?.trim() ?: ""
    }

    /** 标题清洗：去开头的相对日期词，去尾随地点片段，只留"事情" */
    fun cleanTitle(title: String, place: String): String {
        var t = title.replace(Regex("""^(今天|明天|后天|今晚|明早)[，,]?\s*"""), "")
        if (place.isNotEmpty()) {
            val anchor = place.split(" ")[0]
            val idx = t.indexOf(anchor)
            if (idx > 0) t = t.substring(0, idx)
        }
        return t.replace(Regex("""[，,、\s]+$"""), "")
    }
}
